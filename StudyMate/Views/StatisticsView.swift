import SwiftUI
#if os(iOS)
import UIKit
#endif

private enum StudyGrowthPeriod: String, CaseIterable, Identifiable {
    case last30Days
    case last90Days
    case lastYear

    var id: String { rawValue }

    func title(strings: AppStrings) -> String {
        switch self {
        case .last30Days:
            strings.last30Days
        case .last90Days:
            strings.last90Days
        case .lastYear:
            strings.lastYear
        }
    }

    func bounds(now: Date = Date()) -> (startAt: Date, endAt: Date) {
        let days: Int
        switch self {
        case .last30Days:
            days = 30
        case .last90Days:
            days = 90
        case .lastYear:
            days = 365
        }
        return (
            Calendar.current.date(byAdding: .day, value: -days, to: now)
                ?? now.addingTimeInterval(-Double(days) * 86_400),
            now
        )
    }
}

struct StatisticsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedRecord: StudyRecord?
    @State private var topicPage = 0
    @State private var selectedActivityYear = Calendar.current.component(.year, from: Date())
    @State private var selectedGrowthPeriod: StudyGrowthPeriod = .last90Days
    @State private var isShowingGrowthHelp = false

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

    private var displayedStudyGrowth: BackendStudyGrowth? {
        if let growth = appState.backendStudyGrowth {
            return growth
        }
        let bounds = selectedGrowthPeriod.bounds()
        return LegacyStudyGrowthProjection.make(
            rooms: appState.backendStudyRooms,
            records: visibleStatRecords,
            startAt: bounds.startAt,
            endAt: bounds.endAt
        )
    }

    private var activityYearOptions: [Int] {
        let calendar = Calendar.current
        let currentYear = calendar.component(.year, from: Date())
        let joinedYear = appState.backendAccessState.user.createdAt
            .map { calendar.component(.year, from: $0) } ?? currentYear
        return Array(Array(min(joinedYear, currentYear)...currentYear).reversed())
    }

    var body: some View {
        let strings = appState.strings
        let count = responseCount
        let pageCount = topicPageCount
        let years = activityYearOptions
        let studyGrowth = displayedStudyGrowth

        VStack(spacing: 0) {
            MobileRootLargeTitle(strings.tabStatistics)
                .padding(.top, 6)
                .padding(.bottom, 8)

            StatsYearSelector(
                selectedYear: $selectedActivityYear,
                years: years,
                strings: strings
            )
            .padding(.bottom, 10)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
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
                            topics: topicStats,
                            studyGrowth: studyGrowth,
                            activity: appState.backendStatsActivity,
                            isActivityLoading: appState.isBackendStatsActivityLoading,
                            selectedYear: selectedActivityYear,
                            strings: strings
                        )

                        StudyGrowthPeriodPicker(
                            selection: $selectedGrowthPeriod,
                            strings: strings
                        )

                        HStack(spacing: 7) {
                            Text(strings.studyGrowth)
                                .font(.title3.weight(.bold))

                            Button {
                                isShowingGrowthHelp = true
                            } label: {
                                Image(systemName: "questionmark.circle")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(strings.growthCalculationHelp)

                            Spacer()
                        }
                        .padding(.top, 2)

                        if appState.isBackendStudyGrowthLoading && studyGrowth == nil {
                            ProgressView()
                                .controlSize(.small)
                                .frame(maxWidth: .infinity, minHeight: 180)
                        } else if let growth = studyGrowth {
                            StudyGrowthRootSection(growth: growth, strings: strings)
                        } else {
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

                        if let growthError = appState.backendStudyGrowthErrorMessage,
                           appState.backendStudyGrowth != nil {
                            Text(growthError)
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .padding(.top, 8)
                .padding(.trailing, 8)
                .padding(.bottom, 24)
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .refreshable {
                await refreshStats()
            }
            .searchSafeRefreshControlOffset()
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .recordDetailPresentation(selectedRecord: $selectedRecord, strings: strings)
        .sheet(isPresented: $isShowingGrowthHelp) {
            StudyGrowthHelpView(strings: strings)
        }
        .onChange(of: selectedActivityYear) {
            resetTopicPaging()
            loadStats()
            loadActivity()
        }
        .onChange(of: selectedGrowthPeriod) {
            loadStudyGrowth()
        }
        .onChange(of: appState.backendAccessState.user.createdAt) {
            let years = activityYearOptions
            if let newest = years.first, !years.contains(selectedActivityYear) {
                selectedActivityYear = newest
            }
        }
        .onAppear {
            if let newest = activityYearOptions.first, !activityYearOptions.contains(selectedActivityYear) {
                selectedActivityYear = newest
            }
            loadStats()
            loadActivity()
            loadStudyGrowth()
        }
    }

    private func loadStats() {
        let bounds = activityYearBounds(for: selectedActivityYear)
        loadStats(
            startAt: bounds.startAt,
            endAt: bounds.endAt,
            limit: Self.topicPageSize,
            offset: max(topicPage * Self.topicPageSize, 0)
        )
    }

    private func loadActivity() {
        let bounds = activityYearBounds(for: selectedActivityYear)
        Task {
            await appState.fetchBackendStatsActivity(startAt: bounds.startAt, endAt: bounds.endAt)
        }
    }

    private func loadStudyGrowth() {
        let bounds = selectedGrowthPeriod.bounds()
        Task {
            await appState.fetchBackendStudyGrowth(startAt: bounds.startAt, endAt: bounds.endAt)
        }
    }

    private func loadStats(
        startAt: Date? = nil,
        endAt: Date? = nil,
        limit: Int = Self.topicPageSize,
        offset: Int = 0
    ) {
        let requestOffset = max(offset, 0)
        Task {
            await appState.fetchBackendStats(
                period: .all,
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
        let bounds = activityYearBounds(for: selectedActivityYear)
        await appState.fetchBackendStats(
            period: .all,
            sort: .count,
            startAt: bounds.startAt,
            endAt: bounds.endAt,
            limit: Self.topicPageSize,
            offset: max(topicPage * Self.topicPageSize, 0)
        )
        await appState.fetchBackendStatsActivity(startAt: bounds.startAt, endAt: bounds.endAt)
        let growthBounds = selectedGrowthPeriod.bounds()
        await appState.fetchBackendStudyGrowth(
            startAt: growthBounds.startAt,
            endAt: growthBounds.endAt
        )
    }

    private func resetTopicPaging() {
        topicPage = 0
    }

    private func activityYearBounds(for year: Int) -> (startAt: Date?, endAt: Date?) {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(secondsFromGMT: 0)
        components.year = year
        components.month = 1
        components.day = 1
        let calendar = components.calendar ?? Calendar.current
        guard let startAt = calendar.date(from: components),
              let endAt = calendar.date(byAdding: .year, value: 1, to: startAt) else {
            return (nil, nil)
        }
        return (startAt, endAt)
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
                            MarkdownMessageText(markdown: displayedRecord.question.question)
                                .font(.body)
                                .foregroundStyle(.white)
                                .tint(.white)
                                .textSelection(.enabled)

                            hintView(for: displayedRecord)
                        }
                    }

                    if let answer = displayedRecord.answer,
                       !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                       displayedRecord.gradingResult != nil {
                        RecordChatBubble(role: .answer) {
                            MarkdownMessageText(markdown: answer, fillsWidth: false)
                                .font(.body)
                                .foregroundStyle(.white)
                                .tint(.white)
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

                                MarkdownMessageText(markdown: result.feedback)
                                    .font(.body)
                                    .textSelection(.enabled)

                                MarkdownMessageText(markdown: result.explanation)
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
                StudyRecordIdentityPolicy.questionsMatch($0.question.question, record.question.question)
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
                    MarkdownMessageText(markdown: hint)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                        .tint(.white)
                        .textSelection(.enabled)
                        .lineLimit(nil)
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

private struct StatsYearSelector: View {
    @Binding var selectedYear: Int
    var years: [Int]
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 8) {
            Text(strings.year)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(years, id: \.self) { year in
                        Button {
                            selectedYear = year
                        } label: {
                            Text(String(year))
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(selectedYear == year ? Color.white : Color.primary)
                                .padding(.horizontal, 13)
                                .padding(.vertical, 8)
                                .background(
                                    Capsule(style: .continuous)
                                        .fill(selectedYear == year ? Color.accentColor : Color.secondary.opacity(0.11))
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(.horizontal, 2)
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
        // Product rule: 50 points stays at the current level, 80 points moves to +1 level.
        let levelValue = Double(difficulty.level) + (Double(clampedScore) - 50) / 30
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
        let centerLevel = min(max(backendRange.centerLevel, 1), 10)
        let lowerBound = normalizeProgress(backendRange.lowerBound)
        let upperBound = normalizeProgress(backendRange.upperBound)

        return TopicLevelRange(
            level: Difficulty(level: backendRange.level),
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
    var topics: [TopicStat]
    var studyGrowth: BackendStudyGrowth?
    var activity: BackendStatsActivity?
    var isActivityLoading: Bool
    var selectedYear: Int
    var strings: AppStrings

    var body: some View {
        let achievements = StatsAchievementSnapshot(
            activity: activity,
            topics: topics,
            studyGrowth: studyGrowth,
            selectedYear: selectedYear,
            strings: strings
        )

        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top, spacing: 0) {
                StatsAchievementCard(
                    title: strings.studyStreak,
                    value: achievements.streakValue,
                    caption: achievements.streakCaption
                )

                Divider()
                    .frame(height: 58)

                StatsAchievementCard(
                    title: strings.studyGrowth,
                    value: achievements.growthValue,
                    caption: achievements.growthCaption
                )

                Divider()
                    .frame(height: 58)

                StatsAchievementCard(
                    title: achievements.periodTitle,
                    value: achievements.monthValue,
                    caption: achievements.monthCaption
                )
            }

            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text(strings.learningActivity)
                        .font(.subheadline.weight(.semibold))

                    Spacer()

                    Text(String(selectedYear))
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }

                if let activity {
                    StatsYearGrass(activity: activity, selectedYear: selectedYear, strings: strings)
                } else if isActivityLoading {
                    ProgressView()
                        .controlSize(.small)
                        .frame(maxWidth: .infinity, minHeight: 78)
                } else {
                    Text(strings.noActivityYet)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 78, alignment: .center)
                }
            }
        }
        .padding(20)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

private struct StatsAchievementCard: View {
    var title: String
    var value: String
    var caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text(value)
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .allowsTightening(true)

            Text(caption)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, 10)
    }
}

private struct StatsAchievementSnapshot {
    var streakValue: String
    var streakCaption: String
    var growthValue: String
    var growthCaption: String
    var periodTitle: String
    var monthValue: String
    var monthCaption: String

    init(
        activity: BackendStatsActivity?,
        topics: [TopicStat],
        studyGrowth: BackendStudyGrowth?,
        selectedYear: Int,
        strings: AppStrings
    ) {
        let currentYear = Calendar.current.component(.year, from: Date())
        let isCurrentYear = selectedYear == currentYear
        let streakDays = isCurrentYear ? (activity?.streakDays ?? 0) : Self.longestStreak(from: activity)
        streakValue = strings.streakValue(streakDays)
        streakCaption = isCurrentYear
            ? (streakDays > 0 ? strings.streakKeepGoing : strings.streakStartToday)
            : strings.longestStreak

        if let growth = studyGrowth?.roots
            .filter({ $0.growth != nil })
            .max(by: { ($0.growth ?? 0) < ($1.growth ?? 0) }),
           let delta = growth.growth {
            growthValue = Self.signed(delta)
            growthCaption = growth.topic
        } else if let growth = Self.bestGrowth(from: topics) {
            growthValue = Self.signed(growth.delta)
            growthCaption = growth.topic
        } else if let topTopic = topics.max(by: { $0.count < $1.count }) {
            growthValue = "\(strings.level) \(Self.levelFormatter.string(from: NSNumber(value: topTopic.levelRange.centerLevel)) ?? "\(topTopic.levelRange.level.level)")"
            growthCaption = topTopic.topic
        } else {
            growthValue = "-"
            growthCaption = strings.noActivityYet
        }

        periodTitle = isCurrentYear ? strings.thisMonth : strings.selectedYear
        monthValue = "\(isCurrentYear ? (activity?.monthAnswerCount ?? 0) : Self.yearAnswerCount(from: activity))"
        let activeDays = isCurrentYear ? Self.currentMonthActiveDays(from: activity) : Self.yearActiveDays(from: activity)
        let focusTopic = isCurrentYear ? Self.currentMonthTopTopic(from: activity) : Self.yearTopTopic(from: activity)
        if let focusTopic {
            monthCaption = isCurrentYear
                ? strings.monthSummaryWithTopic(days: activeDays, topic: focusTopic)
                : strings.yearSummaryWithTopic(days: activeDays, topic: focusTopic)
        } else {
            monthCaption = isCurrentYear ? strings.monthSummary(days: activeDays) : strings.yearSummary(days: activeDays)
        }
    }

    private static func bestGrowth(from topics: [TopicStat]) -> (topic: String, delta: Double)? {
        topics
            .compactMap { stat -> (topic: String, delta: Double)? in
                let records = stat.records
                    .filter { $0.gradingResult?.score != nil }
                    .sorted { statsDate(for: $0) < statsDate(for: $1) }
                guard records.count >= 2 else {
                    return nil
                }

                let splitIndex = max(1, records.count / 2)
                guard
                    let startRange = TopicLevelRange.calculate(records: Array(records.prefix(splitIndex))),
                    let endRange = TopicLevelRange.calculate(records: records)
                else {
                    return nil
                }

                return (stat.topic, endRange.centerLevel - startRange.centerLevel)
            }
            .max { $0.delta < $1.delta }
    }

    private static func signed(_ value: Double) -> String {
        let formatted = levelFormatter.string(from: NSNumber(value: value)) ?? "0"
        return value > 0 ? "+\(formatted)" : formatted
    }

    private static func currentMonthActiveDays(from activity: BackendStatsActivity?) -> Int {
        guard let activity else {
            return 0
        }

        let calendar = Calendar.current
        let now = Date()
        return activity.days.filter { day in
            day.answerCount > 0
                && calendar.component(.year, from: day.date) == calendar.component(.year, from: now)
                && calendar.component(.month, from: day.date) == calendar.component(.month, from: now)
        }
        .count
    }

    private static func currentMonthTopTopic(from activity: BackendStatsActivity?) -> String? {
        guard let activity else {
            return nil
        }

        let calendar = Calendar.current
        let now = Date()
        var counts: [String: Int] = [:]
        activity.days
            .filter {
                calendar.component(.year, from: $0.date) == calendar.component(.year, from: now)
                    && calendar.component(.month, from: $0.date) == calendar.component(.month, from: now)
            }
            .flatMap(\.topics)
            .forEach { counts[$0, default: 0] += 1 }

        return counts.max { $0.value < $1.value }?.key
    }

    private static func yearAnswerCount(from activity: BackendStatsActivity?) -> Int {
        activity?.days.reduce(0) { $0 + $1.answerCount } ?? 0
    }

    private static func yearActiveDays(from activity: BackendStatsActivity?) -> Int {
        activity?.days.filter { $0.answerCount > 0 }.count ?? 0
    }

    private static func yearTopTopic(from activity: BackendStatsActivity?) -> String? {
        guard let activity else {
            return nil
        }

        var counts: [String: Int] = [:]
        activity.days
            .flatMap(\.topics)
            .forEach { counts[$0, default: 0] += 1 }

        return counts.max { $0.value < $1.value }?.key
    }

    private static func longestStreak(from activity: BackendStatsActivity?) -> Int {
        guard let activity else {
            return 0
        }

        var best = 0
        var current = 0
        for day in activity.days.sorted(by: { $0.date < $1.date }) {
            if day.answerCount > 0 {
                current += 1
                best = max(best, current)
            } else {
                current = 0
            }
        }
        return best
    }

    private static func statsDate(for record: StudyRecord) -> Date {
        record.answeredAt ?? record.question.createdAt
    }

    private static let levelFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        return formatter
    }()
}

private struct StatsYearGrass: View {
    var activity: BackendStatsActivity
    var selectedYear: Int
    var strings: AppStrings
    @State private var selectedDay: BackendStatsActivityDay?

    var body: some View {
        let weeks = Self.weeks(from: activity.days, selectedYear: selectedYear)

        VStack(alignment: .leading, spacing: 8) {
            ScrollViewReader { proxy in
                HStack(alignment: .top, spacing: 6) {
                    VStack(spacing: Self.cellSpacing) {
                        Color.clear
                            .frame(width: Self.weekdayLabelWidth, height: Self.monthLabelHeight)
                        ForEach(Self.weekdayLabels, id: \.self) { label in
                            Text(label)
                                .font(.system(size: 8, weight: .medium))
                                .foregroundStyle(.secondary)
                                .frame(width: Self.weekdayLabelWidth, height: Self.cellSize, alignment: .trailing)
                        }
                    }

                    ScrollView(.horizontal, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 5) {
                            HStack(alignment: .top, spacing: Self.cellSpacing) {
                                ForEach(weeks) { week in
                                    Text(week.monthLabel)
                                        .font(.system(size: 8, weight: .medium))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                        .fixedSize(horizontal: true, vertical: false)
                                        .frame(width: Self.cellSize, height: Self.monthLabelHeight, alignment: .leading)
                                        .zIndex(week.monthLabel.isEmpty ? 0 : 1)
                                }
                            }

                            HStack(alignment: .top, spacing: Self.cellSpacing) {
                                ForEach(weeks) { week in
                                    VStack(spacing: Self.cellSpacing) {
                                        ForEach(week.days) { day in
                                            Button {
                                                withAnimation(.smooth(duration: 0.16)) {
                                                    selectedDay = day
                                                }
                                            } label: {
                                                RoundedRectangle(cornerRadius: 2, style: .continuous)
                                                    .fill(color(for: day.answerCount))
                                                    .frame(width: Self.cellSize, height: Self.cellSize)
                                                    .overlay {
                                                        if selectedDay?.id == day.id {
                                                            RoundedRectangle(cornerRadius: 2, style: .continuous)
                                                                .stroke(Color.primary.opacity(0.75), lineWidth: 1)
                                                        }
                                                    }
                                            }
                                            .buttonStyle(.plain)
                                            .accessibilityLabel(accessibilityText(for: day))
                                        }
                                    }
                                    .id(week.id)
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
                .onAppear {
                    scrollToLatestWeek(proxy, weeks: weeks)
                }
                .onChange(of: selectedYear) {
                    scrollToLatestWeek(proxy, weeks: weeks)
                }
                .onChange(of: activity.days.count) {
                    scrollToLatestWeek(proxy, weeks: weeks)
                }
            }

            if let selectedDay {
                Text(displayText(for: selectedDay))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func scrollToLatestWeek(_ proxy: ScrollViewProxy, weeks: [ActivityWeek]) {
        guard let lastID = weeks.last?.id else {
            return
        }
        DispatchQueue.main.async {
            withAnimation(.smooth(duration: 0.2)) {
                proxy.scrollTo(lastID, anchor: .trailing)
            }
        }
    }

    private func color(for count: Int) -> Color {
        switch count {
        case 6...:
            return Color.green.opacity(0.94)
        case 4...5:
            return Color.green.opacity(0.74)
        case 2...3:
            return Color.green.opacity(0.54)
        case 1:
            return Color.green.opacity(0.31)
        default:
            return Color.secondary.opacity(0.13)
        }
    }

    private func displayText(for day: BackendStatsActivityDay) -> String {
        var parts = ["\(Self.dateFormatter.string(from: day.date))", "\(day.answerCount) \(strings.answersUnit)"]
        if let bestLevel = day.bestLevel {
            parts.append("\(strings.level) \(String(format: "%.1f", bestLevel))")
        }
        if let topic = day.topics.first {
            parts.append(topic)
        }
        return parts.joined(separator: " · ")
    }

    private func accessibilityText(for day: BackendStatsActivityDay) -> String {
        "\(Self.dateFormatter.string(from: day.date)), \(day.answerCount) \(strings.answersUnit)"
    }

    private static func weeks(from days: [BackendStatsActivityDay], selectedYear: Int) -> [ActivityWeek] {
        let calendar = Self.weekCalendar

        var yearStartComponents = DateComponents()
        yearStartComponents.calendar = calendar
        yearStartComponents.timeZone = calendar.timeZone
        yearStartComponents.year = selectedYear
        yearStartComponents.month = 1
        yearStartComponents.day = 1

        guard let yearStart = calendar.date(from: yearStartComponents),
              let yearEnd = calendar.date(byAdding: DateComponents(year: 1, day: -1), to: yearStart) else {
            return []
        }

        let today = calendar.startOfDay(for: Date())
        let currentYear = calendar.component(.year, from: today)
        let lastContentDay = selectedYear == currentYear ? min(today, yearEnd) : yearEnd
        guard yearStart <= lastContentDay else {
            return []
        }

        let daysByStartOfDay = days.reduce(into: [Date: BackendStatsActivityDay]()) { result, day in
            let key = calendar.startOfDay(for: day.date)
            result[key] = day
        }

        let dayCount = calendar.dateComponents([.day], from: yearStart, to: lastContentDay).day ?? 0
        let contentDays = (0...max(dayCount, 0)).compactMap { offset -> BackendStatsActivityDay? in
            guard let date = calendar.date(byAdding: .day, value: offset, to: yearStart) else {
                return nil
            }
            return daysByStartOfDay[date] ?? BackendStatsActivityDay(date: date, answerCount: 0, topicCount: 0, topics: [], bestLevel: nil)
        }

        let weekday = calendar.component(.weekday, from: yearStart)
        let leadingEmptyCount = (weekday - calendar.firstWeekday + 7) % 7
        let emptyDays = (0..<leadingEmptyCount).compactMap { offset -> BackendStatsActivityDay? in
            guard let date = calendar.date(byAdding: .day, value: -(leadingEmptyCount - offset), to: yearStart) else {
                return nil
            }
            return BackendStatsActivityDay(date: date, answerCount: 0, topicCount: 0, topics: [], bestLevel: nil)
        }

        var paddedDays = emptyDays + contentDays
        let trailingEmptyCount = (7 - (paddedDays.count % 7)) % 7
        if let last = paddedDays.last, trailingEmptyCount > 0 {
            let trailingDays = (1...trailingEmptyCount).compactMap { offset -> BackendStatsActivityDay? in
                guard let date = calendar.date(byAdding: .day, value: offset, to: last.date) else {
                    return nil
                }
                return BackendStatsActivityDay(date: date, answerCount: 0, topicCount: 0, topics: [], bestLevel: nil)
            }
            paddedDays.append(contentsOf: trailingDays)
        }
        return stride(from: 0, to: paddedDays.count, by: 7).map { start in
            let slice = Array(paddedDays[start..<min(start + 7, paddedDays.count)])
            return ActivityWeek(days: slice, selectedYear: selectedYear)
        }
    }

    private static let cellSize: CGFloat = 11
    private static let cellSpacing: CGFloat = 3
    private static let monthLabelHeight: CGFloat = 12
    private static let weekdayLabelWidth: CGFloat = 18
    private static let weekdayLabels = ["월", "화", "수", "목", "금", "토", "일"]
    private static var weekCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "ko_KR")
        calendar.timeZone = .current
        calendar.firstWeekday = 2
        return calendar
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "M/d"
        return formatter
    }()
}

private struct ActivityWeek: Identifiable {
    var days: [BackendStatsActivityDay]
    var selectedYear: Int

    var id: Date {
        days.first?.date ?? Date.distantPast
    }

    var monthLabel: String {
        let calendar = Self.weekCalendar
        guard let monthStart = days.first(where: {
            calendar.component(.year, from: $0.date) == selectedYear &&
                calendar.component(.day, from: $0.date) == 1
        }) else {
            return ""
        }
        return "\(calendar.component(.month, from: monthStart.date))"
    }

    private static var weekCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "ko_KR")
        calendar.timeZone = .current
        calendar.firstWeekday = 2
        return calendar
    }
}

private struct StudyGrowthPeriodPicker: View {
    @Binding var selection: StudyGrowthPeriod
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 8) {
            ForEach(StudyGrowthPeriod.allCases) { period in
                Button {
                    selection = period
                } label: {
                    Text(period.title(strings: strings))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(selection == period ? Color.white : Color.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(
                            selection == period
                                ? Color.accentColor
                                : Color.secondary.opacity(0.11),
                            in: Capsule(style: .continuous)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct StudyGrowthHelpView: View {
    var strings: AppStrings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    growthHelpRow(
                        number: "1",
                        title: strings.growthHelpAbilityTitle,
                        body: strings.growthHelpAbilityBody
                    )
                    growthHelpRow(
                        number: "2",
                        title: strings.growthHelpComparisonTitle,
                        body: strings.growthHelpComparisonBody
                    )
                    growthHelpRow(
                        number: "3",
                        title: strings.growthHelpTreeTitle,
                        body: strings.growthHelpTreeBody
                    )
                    growthHelpRow(
                        number: "4",
                        title: strings.growthHelpProfileTitle,
                        body: strings.growthHelpProfileBody
                    )
                }
            }
            .navigationTitle(strings.growthCalculationHelp)
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

    private func growthHelpRow(number: String, title: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number)
                .font(.caption.weight(.bold))
                .foregroundStyle(Color.accentColor)
                .frame(width: 26, height: 26)
                .background(Color.accentColor.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(body)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 5)
    }
}

private struct StudyGrowthRootSection: View {
    var growth: BackendStudyGrowth
    var strings: AppStrings

    var body: some View {
        if growth.roots.isEmpty {
            ContentUnavailableView(
                strings.noGrowthRecords,
                systemImage: "chart.line.uptrend.xyaxis",
                description: Text(strings.noGrowthRecordsDescription)
            )
            .frame(maxWidth: .infinity, minHeight: 210)
        } else {
            VStack(spacing: 0) {
                HStack {
                    Text(strings.abilityScale)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)

                    Spacer()

                    HStack(spacing: 12) {
                        Label(strings.previousAbility, systemImage: "circle")
                        Label(strings.currentAbility, systemImage: "circle.fill")
                    }
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 8)

                ForEach(Array(growth.roots.enumerated()), id: \.element.id) { index, root in
                    NavigationLink {
                        StudyGrowthDetailView(
                            growth: growth,
                            rootStudyID: root.studyId,
                            strings: strings
                        )
                    } label: {
                        StudyGrowthOverviewRow(root: root, strings: strings)
                    }
                    .buttonStyle(.plain)

                    if index < growth.roots.count - 1 {
                        Divider()
                            .padding(.leading, 16)
                    }
                }
            }
            .background(Color(.secondarySystemBackground))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}

private struct StudyGrowthOverviewRow: View {
    var root: BackendStudyGrowthRoot
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(root.topic)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Spacer(minLength: 8)

                StudyGrowthDeltaLabel(growth: root.growth, strings: strings)

                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.tertiary)
            }

            StudyGrowthRangeTrack(
                previous: root.previousLevel,
                current: root.currentLevel,
                growth: root.growth
            )
            .frame(height: 28)

            HStack {
                Text(strings.growthPositionSummary(
                    previous: StudyGrowthFormat.level(root.previousLevel),
                    current: StudyGrowthFormat.level(root.currentLevel)
                ))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)

                Spacer()

                Text(strings.measuredTopics(root.measuredTopicCount, total: root.totalTopicCount))
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private struct StudyGrowthRangeTrack: View {
    var previous: Double?
    var current: Double?
    var growth: Double?

    var body: some View {
        GeometryReader { proxy in
            let width = max(proxy.size.width - 12, 1)
            let midY = proxy.size.height / 2
            let previousX = xPosition(previous, width: width)
            let currentX = xPosition(current, width: width)
            let color = StudyGrowthFormat.color(growth)

            Capsule()
                .fill(Color.secondary.opacity(0.14))
                .frame(height: 4)
                .position(x: proxy.size.width / 2, y: midY)

            if let previousX, let currentX {
                Path { path in
                    path.move(to: CGPoint(x: previousX + 6, y: midY))
                    path.addLine(to: CGPoint(x: currentX + 6, y: midY))
                }
                .stroke(color, style: StrokeStyle(lineWidth: 5, lineCap: .round))

                Circle()
                    .fill(Color(.secondarySystemBackground))
                    .stroke(Color.secondary, lineWidth: 2)
                    .frame(width: 11, height: 11)
                    .position(x: previousX + 6, y: midY)

                Circle()
                    .fill(color)
                    .frame(width: 13, height: 13)
                    .position(x: currentX + 6, y: midY)
            } else if let currentX {
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 13, height: 13)
                    .position(x: currentX + 6, y: midY)
            }

            Text("1")
                .font(.system(size: 9, weight: .medium, design: .rounded))
                .foregroundStyle(.tertiary)
                .position(x: 6, y: proxy.size.height - 2)

            Text("10")
                .font(.system(size: 9, weight: .medium, design: .rounded))
                .foregroundStyle(.tertiary)
                .position(x: proxy.size.width - 7, y: proxy.size.height - 2)
        }
        .accessibilityHidden(true)
    }

    private func xPosition(_ value: Double?, width: CGFloat) -> CGFloat? {
        guard let value else {
            return nil
        }
        return width * CGFloat((min(max(value, 1), 10) - 1) / 9)
    }
}

private struct StudyGrowthDetailView: View {
    var growth: BackendStudyGrowth
    var rootStudyID: Int
    var strings: AppStrings

    private var root: BackendStudyGrowthRoot? {
        growth.roots.first { $0.studyId == rootStudyID }
    }

    private var rootNode: BackendStudyGrowthNode? {
        growth.nodes.first { $0.studyId == rootStudyID }
    }

    private var nodes: [BackendStudyGrowthNode] {
        growth.nodes.filter { $0.rootStudyId == rootStudyID }
    }

    private var orderedNodes: [BackendStudyGrowthNode] {
        let children = Dictionary(grouping: nodes.filter { $0.parentStudyId != nil }) {
            $0.parentStudyId ?? -1
        }
        var result: [BackendStudyGrowthNode] = []
        var visited = Set<Int>()

        func append(_ node: BackendStudyGrowthNode) {
            guard visited.insert(node.studyId).inserted else {
                return
            }
            result.append(node)
            for child in (children[node.studyId] ?? []).sorted(by: nodeOrdering) {
                append(child)
            }
        }

        if let rootNode {
            append(rootNode)
        }
        for node in nodes.sorted(by: nodeOrdering) where !visited.contains(node.studyId) {
            append(node)
        }
        return result
    }

    var body: some View {
        VStack(spacing: 0) {
            if let root, let rootNode {
                StudyGrowthTreeCard(
                    root: root,
                    rootNode: rootNode,
                    orderedNodes: orderedNodes,
                    strings: strings
                )
                .frame(maxHeight: .infinity)
            } else {
                ContentUnavailableView(
                    strings.noGrowthRecords,
                    systemImage: "point.3.connected.trianglepath.dotted",
                    description: Text(strings.noGrowthRecordsDescription)
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(root?.topic ?? strings.growthDetails)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private func nodeOrdering(
        _ lhs: BackendStudyGrowthNode,
        _ rhs: BackendStudyGrowthNode
    ) -> Bool {
        if lhs.sortOrder != rhs.sortOrder {
            return lhs.sortOrder < rhs.sortOrder
        }
        return lhs.studyId < rhs.studyId
    }
}

private struct StudyGrowthTreeItem: Identifiable {
    var node: BackendStudyGrowthNode
    var localDepth: Int
    var continuingAncestorDepths: Set<Int>
    var isLastSibling: Bool

    var id: Int { node.studyId }
}

private struct StudyGrowthTreeCard: View {
    @EnvironmentObject private var appState: AppState
    @State private var zoomMultiplier: CGFloat = 1
    @State private var zoomStartMultiplier: CGFloat = 1
    @State private var isZoomGestureActive = false

    var root: BackendStudyGrowthRoot
    var rootNode: BackendStudyGrowthNode
    var orderedNodes: [BackendStudyGrowthNode]
    var strings: AppStrings

    private var nodesByID: [Int: BackendStudyGrowthNode] {
        Dictionary(uniqueKeysWithValues: orderedNodes.map { ($0.studyId, $0) })
    }

    private var snapshot: StudyTreeLayoutSnapshot? {
        guard let rootRoom = appState.backendStudyRoom(id: root.studyId) else {
            return nil
        }
        return StudyTreeLayoutSnapshot(
            root: rootRoom,
            rooms: appState.backendStudyRooms
        )
    }

    private var nodeOffsets: [Int: CGSize] {
        appState.loadStudyTreeNodeOffsets(rootStudyID: root.studyId)
    }

    var body: some View {
        VStack(spacing: 0) {
            summary

            Divider()
                .padding(.leading, 18)

            if let snapshot {
                treeCanvas(snapshot)
            } else {
                ContentUnavailableView(
                    strings.noGrowthRecords,
                    systemImage: "point.3.connected.trianglepath.dotted"
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color(.secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(strings.studyStatusTree)
                        .font(.headline)
                    Text(strings.studyStatusTreeDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                Text(strings.abilityScale)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                StudyGrowthSummaryMetric(
                    value: "\(root.measuredTopicCount)/\(root.totalTopicCount)",
                    label: strings.measuredStudyShort
                )
                StudyGrowthSummaryMetric(
                    value: strings.growthCompletionValue(root.profile?.completion),
                    label: strings.completion
                )
                StudyGrowthSummaryMetric(
                    value: "\(root.answerCount)",
                    label: strings.answersUnit
                )
            }
        }
        .padding(18)
    }

    private func treeCanvas(_ snapshot: StudyTreeLayoutSnapshot) -> some View {
        GeometryReader { geometry in
            let canvasLayout = StudyTreeCanvasPolicy.expandedLayout(
                baseCenters: snapshot.centerByRoomID,
                nodeOffsets: nodeOffsets,
                baseCanvasSize: snapshot.size,
                nodeSize: StudyTreeLayoutSnapshot.nodeSize
            )
            let fittedScale = StudyTreeViewportPolicy.fittedZoomScale(
                canvasSize: canvasLayout.size,
                viewportSize: geometry.size
            )
            let zoomScale = min(
                max(
                    fittedScale * zoomMultiplier,
                    StudyTreeViewportPolicy.minimumZoomScale
                ),
                StudyTreeViewportPolicy.maximumZoomScale
            )
            let scaledCanvasSize = CGSize(
                width: canvasLayout.size.width * zoomScale,
                height: canvasLayout.size.height * zoomScale
            )

            ZStack {
                ScrollView([.horizontal, .vertical]) {
                    ZStack(alignment: .topLeading) {
                        Canvas { context, _ in
                            for edge in snapshot.edges {
                                let parent = renderedCenter(
                                    edge.parent,
                                    roomID: edge.parentID,
                                    canvasLayout: canvasLayout
                                )
                                let child = renderedCenter(
                                    edge.child,
                                    roomID: edge.childID,
                                    canvasLayout: canvasLayout
                                )
                                guard let edgeGeometry = StudyTreeEdgePolicy.directionalGeometry(
                                    parent: parent,
                                    child: child,
                                    nodeRadius: StudyTreeLayoutSnapshot.nodeSize.width / 2 + 4
                                ) else {
                                    continue
                                }

                                var path = Path()
                                path.move(to: edgeGeometry.start)
                                let midpoint = (edgeGeometry.start.y + edgeGeometry.end.y) / 2
                                path.addCurve(
                                    to: edgeGeometry.end,
                                    control1: CGPoint(x: edgeGeometry.start.x, y: midpoint),
                                    control2: CGPoint(x: edgeGeometry.end.x, y: midpoint)
                                )
                                let edgeColor = Color.secondary.opacity(0.48)
                                context.stroke(path, with: .color(edgeColor), lineWidth: 1.7)

                                var arrow = Path()
                                arrow.move(to: edgeGeometry.end)
                                arrow.addLine(to: edgeGeometry.arrowLeft)
                                arrow.addLine(to: edgeGeometry.arrowRight)
                                arrow.closeSubpath()
                                context.fill(arrow, with: .color(edgeColor))
                            }
                        }

                        ForEach(snapshot.placements) { placement in
                            if let node = nodesByID[placement.id] {
                                NavigationLink {
                                    StudyGrowthNodeDetailView(node: node, strings: strings)
                                        .padding(.horizontal, 16)
                                } label: {
                                    StudyGrowthScoreTreeNode(
                                        topic: node.topic,
                                        currentLevel: node.studyId == root.studyId
                                            ? root.currentLevel
                                            : node.currentLevel,
                                        growth: node.studyId == root.studyId
                                            ? root.growth
                                            : node.growth,
                                        isRoot: node.studyId == root.studyId,
                                        strings: strings
                                    )
                                }
                                .buttonStyle(.plain)
                                .position(
                                    renderedCenter(
                                        placement.center,
                                        roomID: placement.id,
                                        canvasLayout: canvasLayout
                                    )
                                )
                            }
                        }
                    }
                    .frame(width: canvasLayout.size.width, height: canvasLayout.size.height)
                    .scaleEffect(zoomScale, anchor: .topLeading)
                    .frame(
                        width: scaledCanvasSize.width,
                        height: scaledCanvasSize.height,
                        alignment: .topLeading
                    )
                    .frame(
                        width: max(geometry.size.width, scaledCanvasSize.width),
                        height: max(geometry.size.height, scaledCanvasSize.height),
                        alignment: .center
                    )
                }
                .simultaneousGesture(zoomGesture)
            }
        }
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                if !isZoomGestureActive {
                    isZoomGestureActive = true
                    zoomStartMultiplier = zoomMultiplier
                }
                zoomMultiplier = min(
                    max(zoomStartMultiplier * value.magnification, 0.6),
                    3
                )
            }
            .onEnded { _ in
                isZoomGestureActive = false
                zoomStartMultiplier = zoomMultiplier
            }
    }

    private func renderedCenter(
        _ center: CGPoint,
        roomID: Int,
        canvasLayout: StudyTreeCanvasLayout
    ) -> CGPoint {
        let offset = StudyTreeCanvasPolicy.sanitizedOffset(nodeOffsets[roomID] ?? .zero)
        return CGPoint(
            x: center.x + offset.width + canvasLayout.translation.width,
            y: center.y + offset.height + canvasLayout.translation.height
        )
    }

}

private struct StudyGrowthScoreTreeNode: View {
    var topic: String
    var currentLevel: Double?
    var growth: Double?
    var isRoot: Bool
    var strings: AppStrings

    private var nodeColor: Color {
        guard currentLevel != nil else {
            return .secondary.opacity(0.65)
        }
        if let growth, growth < -0.15 {
            return .orange
        }
        return .accentColor
    }

    private var fillFraction: CGFloat {
        guard let currentLevel else {
            return 0
        }
        return CGFloat(min(max(currentLevel / 10, 0), 1))
    }

    var body: some View {
        VStack(spacing: 4) {
            Text(topic)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            if isRoot {
                Text(strings.comprehensive)
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Color.accentColor)
            }

            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(StudyGrowthFormat.level(currentLevel))
                    .font(.system(size: 19, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text("/10")
                    .font(.system(size: 8, weight: .semibold, design: .rounded))
            }
            .foregroundStyle(nodeColor)

            Text(growth.map(StudyGrowthFormat.delta) ?? strings.measuringGrowth)
                .font(.caption2.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(StudyGrowthFormat.color(growth))
        }
        .padding(11)
        .frame(
            width: StudyTreeLayoutSnapshot.nodeSize.width,
            height: StudyTreeLayoutSnapshot.nodeSize.height
        )
        .background {
            Circle()
                .fill(Color(.secondarySystemBackground))
        }
        .overlay {
            Circle()
                .strokeBorder(Color.secondary.opacity(0.22), lineWidth: 2.5)
        }
        .overlay {
            Circle()
                .trim(from: 0, to: fillFraction)
                .stroke(
                    nodeColor,
                    style: StrokeStyle(lineWidth: 3, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(topic), \(isRoot ? strings.comprehensive : ""), "
                + "\(strings.currentAbility) \(StudyGrowthFormat.level(currentLevel)), "
                + "\(StudyGrowthFormat.delta(growth))"
        )
    }
}

private struct StudyGrowthTreeNodeRow: View {
    var item: StudyGrowthTreeItem
    var currentLevel: Double?
    var growth: Double?
    var answerCount: Int
    var measuredTopicCount: Int
    var totalTopicCount: Int
    var isRoot: Bool
    var strings: AppStrings

    private var scoreColor: Color {
        guard currentLevel != nil else {
            return .secondary
        }
        if let growth, growth < -0.15 {
            return .orange
        }
        return .accentColor
    }

    var body: some View {
        HStack(spacing: 10) {
            StudyGrowthTreeConnector(
                item: item,
                isMeasured: currentLevel != nil,
                needsReview: growth.map { $0 < -0.15 } ?? false
            )
            .frame(
                width: CGFloat(min(item.localDepth, 6) + 1) * 16,
                height: 70
            )

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(item.node.topic)
                        .font(.subheadline.weight(isRoot ? .bold : .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    if isRoot {
                        Text(strings.comprehensive)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(Color.accentColor)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.accentColor.opacity(0.11), in: Capsule())
                    }
                }

                Text(
                    "\(strings.growthAnswerCount(answerCount)) · "
                        + strings.measuredTopics(measuredTopicCount, total: totalTopicCount)
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }

            Spacer(minLength: 6)

            VStack(alignment: .trailing, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 2) {
                    Text(StudyGrowthFormat.level(currentLevel))
                        .font(.system(size: isRoot ? 23 : 19, weight: .bold, design: .rounded))
                        .monospacedDigit()
                    Text("/10")
                        .font(.system(size: 9, weight: .semibold, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .foregroundStyle(scoreColor)

                StudyGrowthDeltaLabel(growth: growth, strings: strings)
            }

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.bold))
                .foregroundStyle(.tertiary)
        }
        .padding(.trailing, 14)
        .frame(minHeight: 70)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(item.node.topic), \(isRoot ? strings.comprehensive : ""), "
                + "\(strings.currentAbility) \(StudyGrowthFormat.level(currentLevel)), "
                + "\(StudyGrowthFormat.delta(growth))"
        )
    }
}

private struct StudyGrowthTreeConnector: View {
    var item: StudyGrowthTreeItem
    var isMeasured: Bool
    var needsReview: Bool

    private let maximumVisibleDepth = 6
    private let columnWidth: CGFloat = 16

    private var visibleDepth: Int {
        min(item.localDepth, maximumVisibleDepth)
    }

    private var nodeColor: Color {
        if !isMeasured {
            return .secondary.opacity(0.55)
        }
        return needsReview ? .orange : .accentColor
    }

    var body: some View {
        Canvas { context, size in
            let middleY = size.height / 2
            let lineColor = Color.secondary.opacity(0.23)

            for depth in item.continuingAncestorDepths where depth < visibleDepth {
                let x = CGFloat(min(depth, maximumVisibleDepth)) * columnWidth + columnWidth / 2
                var path = Path()
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(path, with: .color(lineColor), lineWidth: 1)
            }

            let nodeX = CGFloat(visibleDepth) * columnWidth + columnWidth / 2
            if visibleDepth > 0 {
                let parentX = CGFloat(visibleDepth - 1) * columnWidth + columnWidth / 2
                var branch = Path()
                branch.move(to: CGPoint(x: parentX, y: 0))
                branch.addLine(
                    to: CGPoint(
                        x: parentX,
                        y: item.isLastSibling ? middleY : size.height
                    )
                )
                branch.move(to: CGPoint(x: parentX, y: middleY))
                branch.addLine(to: CGPoint(x: nodeX, y: middleY))
                context.stroke(branch, with: .color(lineColor), lineWidth: 1)
            }

            let nodeSize: CGFloat = item.localDepth == 0 ? 10 : 8
            context.fill(
                Path(
                    ellipseIn: CGRect(
                        x: nodeX - nodeSize / 2,
                        y: middleY - nodeSize / 2,
                        width: nodeSize,
                        height: nodeSize
                    )
                ),
                with: .color(nodeColor)
            )
        }
        .accessibilityHidden(true)
    }
}

private struct StudyGrowthSummaryCard: View {
    var root: BackendStudyGrowthRoot
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(root.topic)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    Text(strings.studyGrowthSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 4) {
                    Text(StudyGrowthFormat.level(root.currentLevel))
                        .font(.system(size: 29, weight: .bold, design: .rounded))
                        .monospacedDigit()

                    StudyGrowthDeltaLabel(growth: root.growth, strings: strings)
                }
            }

            StudyGrowthSparkline(values: root.trend)
                .frame(height: 48)

            HStack(spacing: 8) {
                StudyGrowthSummaryMetric(
                    value: "\(root.measuredTopicCount)/\(root.totalTopicCount)",
                    label: strings.measuredStudyShort
                )
                StudyGrowthSummaryMetric(
                    value: strings.growthCompletionValue(root.profile?.completion),
                    label: strings.completion
                )
                StudyGrowthSummaryMetric(
                    value: "\(root.answerCount)",
                    label: strings.answersUnit
                )
            }
        }
        .padding(18)
        .background(Color(.secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct StudyGrowthSummaryMetric: View {
    var value: String
    var label: String

    var body: some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.subheadline.weight(.bold))
                .monospacedDigit()
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 11))
    }
}

private struct StudyGrowthMapItem: Identifiable {
    var node: BackendStudyGrowthNode
    var localDepth: Int
    var start: CGFloat
    var width: CGFloat

    var id: Int { node.studyId }
}

private struct StudyGrowthHierarchyMapCard: View {
    var nodes: [BackendStudyGrowthNode]
    var rootNode: BackendStudyGrowthNode
    var strings: AppStrings

    @State private var focusedStudyID: Int?

    private let rowHeight: CGFloat = 44
    private let rowSpacing: CGFloat = 5
    private let depthRailWidth: CGFloat = 28

    private var nodesByID: [Int: BackendStudyGrowthNode] {
        Dictionary(uniqueKeysWithValues: nodes.map { ($0.studyId, $0) })
    }

    private var childrenByID: [Int: [BackendStudyGrowthNode]] {
        Dictionary(grouping: nodes.filter { $0.parentStudyId != nil }) {
            $0.parentStudyId ?? -1
        }
        .mapValues {
            $0.sorted {
                if $0.sortOrder != $1.sortOrder {
                    return $0.sortOrder < $1.sortOrder
                }
                return $0.studyId < $1.studyId
            }
        }
    }

    private var focusNode: BackendStudyGrowthNode {
        if let focusedStudyID, let node = nodesByID[focusedStudyID] {
            return node
        }
        return rootNode
    }

    private var parentNode: BackendStudyGrowthNode? {
        focusNode.parentStudyId.flatMap { nodesByID[$0] }
    }

    private var mapItems: [StudyGrowthMapItem] {
        var result: [StudyGrowthMapItem] = []
        var weightCache: [Int: CGFloat] = [:]

        func leafWeight(_ node: BackendStudyGrowthNode, path: Set<Int>) -> CGFloat {
            if let cached = weightCache[node.studyId] {
                return cached
            }
            guard !path.contains(node.studyId) else {
                return 1
            }
            let children = childrenByID[node.studyId] ?? []
            guard !children.isEmpty else {
                weightCache[node.studyId] = 1
                return 1
            }
            let nextPath = path.union([node.studyId])
            let weight = max(
                children.reduce(CGFloat.zero) { partial, child in
                    partial + leafWeight(child, path: nextPath)
                },
                1
            )
            weightCache[node.studyId] = weight
            return weight
        }

        func append(
            _ node: BackendStudyGrowthNode,
            localDepth: Int,
            start: CGFloat,
            width: CGFloat,
            path: Set<Int>
        ) {
            guard !path.contains(node.studyId) else {
                return
            }
            result.append(
                StudyGrowthMapItem(
                    node: node,
                    localDepth: localDepth,
                    start: start,
                    width: width
                )
            )

            let children = childrenByID[node.studyId] ?? []
            guard !children.isEmpty else {
                return
            }
            let nextPath = path.union([node.studyId])
            let totalWeight = max(
                children.reduce(CGFloat.zero) {
                    $0 + leafWeight($1, path: nextPath)
                },
                1
            )
            var nextStart = start
            for child in children {
                let childWidth = width * leafWeight(child, path: nextPath) / totalWeight
                append(
                    child,
                    localDepth: localDepth + 1,
                    start: nextStart,
                    width: childWidth,
                    path: nextPath
                )
                nextStart += childWidth
            }
        }

        append(focusNode, localDepth: 0, start: 0, width: 1, path: [])
        return result
    }

    private var visibleDepthCount: Int {
        (mapItems.map(\.localDepth).max() ?? 0) + 1
    }

    private var mapHeight: CGFloat {
        CGFloat(visibleDepthCount) * rowHeight
            + CGFloat(max(visibleDepthCount - 1, 0)) * rowSpacing
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(strings.studyMap)
                        .font(.headline)
                    Text(focusNode.topic)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                NavigationLink {
                    StudyGrowthNodeDetailView(node: focusNode, strings: strings)
                } label: {
                    Label(strings.details, systemImage: "arrow.up.right")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderless)
            }

            if let parentNode {
                Button {
                    withAnimation(.easeInOut(duration: 0.22)) {
                        focusedStudyID = parentNode.studyId
                    }
                } label: {
                    Label(strings.moveToParentTopic, systemImage: "chevron.left")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }

            GeometryReader { proxy in
                let mapWidth = max(proxy.size.width - depthRailWidth, 1)

                ZStack(alignment: .topLeading) {
                    ForEach(0..<visibleDepthCount, id: \.self) { depth in
                        Text("\(focusNode.depth + depth + 1)")
                            .font(.caption2.weight(.bold).monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: depthRailWidth - 4, height: rowHeight)
                            .offset(y: CGFloat(depth) * (rowHeight + rowSpacing))
                            .accessibilityHidden(true)
                    }

                    ForEach(mapItems) { item in
                        let itemWidth = max(mapWidth * item.width - 4, 2)

                        Button {
                            guard focusedStudyID != item.node.studyId else {
                                return
                            }
                            withAnimation(.easeInOut(duration: 0.22)) {
                                focusedStudyID = item.node.studyId
                            }
                        } label: {
                            StudyGrowthMapNode(
                                node: item.node,
                                availableWidth: itemWidth,
                                strings: strings
                            )
                        }
                        .buttonStyle(.plain)
                        .frame(width: itemWidth, height: rowHeight)
                        .offset(
                            x: depthRailWidth + mapWidth * item.start + 2,
                            y: CGFloat(item.localDepth) * (rowHeight + rowSpacing)
                        )
                    }
                }
                .id(focusNode.studyId)
                .transition(.opacity)
            }
            .frame(height: mapHeight)
            .clipped()

            HStack(spacing: 12) {
                StudyGrowthMapLegend(color: .accentColor, title: strings.ability)
                StudyGrowthMapLegend(color: .orange, title: strings.needsReview)
                StudyGrowthMapLegend(color: .secondary.opacity(0.45), title: strings.notMeasured)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(18)
        .background(Color(.secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct StudyGrowthMapNode: View {
    var node: BackendStudyGrowthNode
    var availableWidth: CGFloat
    var strings: AppStrings

    private var levelOpacity: Double {
        guard let level = node.currentLevel else {
            return 0.12
        }
        return 0.17 + min(max((level - 1) / 9, 0), 1) * 0.46
    }

    var body: some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(
                    node.currentLevel == nil
                        ? Color.secondary.opacity(0.12)
                        : Color.accentColor.opacity(levelOpacity)
                )

            if availableWidth >= 48 {
                Text(node.topic)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .padding(.horizontal, availableWidth >= 72 ? 10 : 6)
            }

            if let growth = node.growth, growth < -0.15 {
                Rectangle()
                    .fill(Color.orange)
                    .frame(height: 3)
                    .frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Color.primary.opacity(0.05), lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(node.topic), \(strings.currentAbility) \(StudyGrowthFormat.level(node.currentLevel)), \(StudyGrowthFormat.delta(node.growth))"
        )
        .accessibilityHint(strings.studyMapFocusHint)
    }
}

private struct StudyGrowthMapLegend: View {
    var color: Color
    var title: String

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
            Text(title)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

private struct StudyGrowthAttentionCard: View {
    var nodes: [BackendStudyGrowthNode]
    var strings: AppStrings

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text(strings.attentionStudies)
                    .font(.headline)
                Text(strings.attentionStudiesDescription)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)

            Divider()
                .padding(.leading, 14)

            ForEach(Array(nodes.enumerated()), id: \.element.studyId) { index, node in
                if index > 0 {
                    Divider()
                        .padding(.leading, 42)
                }

                NavigationLink {
                    StudyGrowthNodeDetailView(node: node, strings: strings)
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(node.topic)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(attentionReason(node))
                                .font(.caption)
                                .foregroundStyle(attentionColor(node))
                        }

                        Spacer(minLength: 8)

                        Text(StudyGrowthFormat.level(node.currentLevel))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.primary)
                            .monospacedDigit()

                        Image(systemName: "chevron.right")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 62)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.04), lineWidth: 1)
        }
    }

    private func attentionReason(_ node: BackendStudyGrowthNode) -> String {
        if let growth = node.growth, growth < -0.15 {
            return strings.needsReview
        }
        if node.currentLevel == nil {
            return strings.notMeasured
        }
        if node.answerCount < 6 {
            return strings.needsMoreAnswers(node.answerCount)
        }
        return strings.partialMeasurement
    }

    private func attentionColor(_ node: BackendStudyGrowthNode) -> Color {
        if let growth = node.growth, growth < -0.15 {
            return .orange
        }
        return .secondary
    }
}

private struct StudyGrowthFlatNodeRow: View {
    var node: BackendStudyGrowthNode
    var strings: AppStrings

    var body: some View {
        HStack(spacing: 10) {
            Color.clear
                .frame(width: CGFloat(min(node.depth, 5)) * 14)

            VStack(alignment: .leading, spacing: 4) {
                Text(node.topic)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(2)

                Text("\(strings.growthAnswerCount(node.answerCount)) · \(strings.measuredTopics(node.measuredTopicCount, total: node.totalTopicCount))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 3) {
                Text(StudyGrowthFormat.level(node.currentLevel))
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                StudyGrowthDeltaLabel(growth: node.growth, strings: strings)
            }

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.bold))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 70)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private struct StudyGrowthNodeDetailView: View {
    var node: BackendStudyGrowthNode
    var strings: AppStrings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    metric(strings.previousAbility, StudyGrowthFormat.level(node.previousLevel))
                    metric(strings.currentAbility, StudyGrowthFormat.level(node.currentLevel))
                    metric(strings.growthChange, StudyGrowthFormat.delta(node.growth))
                }

                StudyGrowthSparkline(values: node.trend)
                    .frame(height: 120)
                    .padding(16)
                    .background(
                        Color(.secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                    )

                VStack(alignment: .leading, spacing: 8) {
                    Label(
                        strings.measuredTopics(node.measuredTopicCount, total: node.totalTopicCount),
                        systemImage: "point.3.connected.trianglepath.dotted"
                    )
                    Label(strings.growthAnswerCount(node.answerCount), systemImage: "checkmark.circle")
                    if node.totalTopicCount > 1 {
                        Label(strings.includesChildTopics, systemImage: "arrow.triangle.branch")
                    }
                    if let growth = node.growth, growth < -0.15 {
                        Label(strings.needsReview, systemImage: "arrow.counterclockwise")
                            .foregroundStyle(.orange)
                    }
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                )
            }
            .padding(.vertical, 16)
        }
        .navigationTitle(node.topic)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 25, weight: .bold, design: .rounded))
                .monospacedDigit()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
    }
}

private struct StudyGrowthDeltaLabel: View {
    var growth: Double?
    var strings: AppStrings

    var body: some View {
        Text(growth.map(StudyGrowthFormat.delta) ?? strings.measuringGrowth)
            .font(.caption2.weight(.bold))
            .monospacedDigit()
            .foregroundStyle(StudyGrowthFormat.color(growth))
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(StudyGrowthFormat.color(growth).opacity(0.10), in: Capsule())
    }
}

private struct StudyGrowthSparkline: View {
    var values: [Double]

    var body: some View {
        GeometryReader { proxy in
            if values.count < 2 {
                Capsule()
                    .fill(Color.secondary.opacity(0.14))
                    .frame(height: 3)
                    .frame(maxHeight: .infinity, alignment: .center)
            } else {
                Path { path in
                    for (index, value) in values.enumerated() {
                        let x = proxy.size.width * CGFloat(index) / CGFloat(values.count - 1)
                        let normalized = (min(max(value, 1), 10) - 1) / 9
                        let y = proxy.size.height * CGFloat(1 - normalized)
                        if index == 0 {
                            path.move(to: CGPoint(x: x, y: y))
                        } else {
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                    }
                }
                .stroke(
                    Color.accentColor,
                    style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
                )
            }
        }
        .accessibilityHidden(true)
    }
}

private enum StudyGrowthFormat {
    static func level(_ value: Double?) -> String {
        guard let value else {
            return "–"
        }
        return value.formatted(.number.precision(.fractionLength(1)))
    }

    static func delta(_ value: Double?) -> String {
        guard let value else {
            return "–"
        }
        let formatted = abs(value).formatted(.number.precision(.fractionLength(1)))
        if value > 0.05 {
            return "+\(formatted)"
        }
        if value < -0.05 {
            return "-\(formatted)"
        }
        return "0.0"
    }

    static func color(_ value: Double?) -> Color {
        guard let value else {
            return .secondary
        }
        if value > 0.05 {
            return .accentColor
        }
        if value < -0.15 {
            return .orange
        }
        return .secondary
    }
}

private enum LegacyStudyGrowthProjection {
    static func make(
        rooms: [BackendStudyRoom],
        records: [StudyRecord],
        startAt: Date,
        endAt: Date
    ) -> BackendStudyGrowth? {
        guard !rooms.isEmpty else {
            return nil
        }

        let roomsByID = Dictionary(uniqueKeysWithValues: rooms.map { ($0.id, $0) })
        let childrenByParent = Dictionary(grouping: rooms.filter { $0.parentStudyId != nil }) {
            $0.parentStudyId ?? -1
        }
        let filteredRecords = records.filter { record in
            let date = record.answeredAt ?? record.question.createdAt
            return date >= startAt && date < endAt
        }
        let recordsByStudy = Dictionary(grouping: filteredRecords.compactMap { record -> (Int, StudyRecord)? in
            guard let studyID = record.studyID else {
                return nil
            }
            return (studyID, record)
        }, by: \.0).mapValues { pairs in
            pairs.map(\.1)
        }
        var subtreeMemo: [Int: Set<Int>] = [:]

        func subtreeIDs(_ studyID: Int, visiting: Set<Int> = []) -> Set<Int> {
            if let cached = subtreeMemo[studyID] {
                return cached
            }
            guard !visiting.contains(studyID) else {
                return [studyID]
            }
            var result: Set<Int> = [studyID]
            for child in childrenByParent[studyID] ?? [] {
                result.formUnion(subtreeIDs(child.id, visiting: visiting.union([studyID])))
            }
            subtreeMemo[studyID] = result
            return result
        }

        func rootID(_ room: BackendStudyRoom) -> Int {
            var current = room
            var visited = Set<Int>()
            while let parentID = current.parentStudyId,
                  let parent = roomsByID[parentID],
                  visited.insert(current.id).inserted {
                current = parent
            }
            return current.id
        }

        func depth(_ room: BackendStudyRoom) -> Int {
            var current = room
            var value = 0
            var visited = Set<Int>()
            while let parentID = current.parentStudyId,
                  let parent = roomsByID[parentID],
                  visited.insert(current.id).inserted {
                current = parent
                value += 1
            }
            return value
        }

        func metrics(_ room: BackendStudyRoom) -> LegacyGrowthMetrics {
            let subtree = subtreeIDs(room.id)
            let recordsForSubtree: [StudyRecord] = subtree.flatMap { studyID in
                recordsByStudy[studyID] ?? []
            }
            let gradedRecords = recordsForSubtree.filter { record in
                record.gradingResult != nil && record.answeredAt != nil
            }
            let subtreeRecords = gradedRecords.sorted { lhs, rhs in
                let lhsDate = lhs.answeredAt ?? lhs.question.createdAt
                let rhsDate = rhs.answeredAt ?? rhs.question.createdAt
                return lhsDate < rhsDate
            }
            let measuredCount = subtree.reduce(into: 0) { count, studyID in
                if (recordsByStudy[studyID] ?? []).count >= 6 {
                    count += 1
                }
            }
            return LegacyGrowthMetrics(
                records: subtreeRecords,
                measuredTopicCount: measuredCount,
                totalTopicCount: subtree.count
            )
        }

        func average(_ values: [Double]) -> Double? {
            guard !values.isEmpty else {
                return nil
            }
            return values.reduce(0, +) / Double(values.count)
        }

        func profile(_ room: BackendStudyRoom) -> BackendStudyGrowthProfile {
            let subtree = subtreeIDs(room.id)
            let generated = records.filter {
                guard let studyID = $0.studyID, subtree.contains(studyID) else {
                    return false
                }
                return $0.question.createdAt >= startAt && $0.question.createdAt < endAt
            }
            let answered = records.filter {
                guard let studyID = $0.studyID,
                      subtree.contains(studyID),
                      $0.gradingResult != nil,
                      let answeredAt = $0.answeredAt else {
                    return false
                }
                return answeredAt >= startAt && answeredAt < endAt
            }
            let rootDepth = depth(room)
            let deepestTreeLevel = subtree
                .compactMap { roomsByID[$0] }
                .map(depth)
                .max()
                .map { $0 - rootDepth + 1 } ?? 1
            let deepestAnsweredLevel = answered
                .compactMap(\.studyID)
                .compactMap { roomsByID[$0] }
                .map(depth)
                .max()
                .map { $0 - rootDepth + 1 }

            return BackendStudyGrowthProfile(
                achievement: average(
                    answered.compactMap(\.gradingResult?.score).map(Double.init)
                ).map { $0 / 100 },
                challenge: average(
                    answered.map { Double($0.difficulty.level) }
                ).map { $0 / 10 },
                completion: generated.isEmpty
                    ? nil
                    : Double(generated.filter { $0.gradingResult != nil && $0.answeredAt != nil }.count)
                        / Double(generated.count),
                breadth: answered.isEmpty
                    ? nil
                    : Double(Set(answered.compactMap(\.studyID)).count) / Double(max(subtree.count, 1)),
                depth: deepestAnsweredLevel.map { Double($0) / Double(max(deepestTreeLevel, 1)) }
            )
        }

        let nodeResponses = rooms.map { room -> BackendStudyGrowthNode in
            let value = metrics(room)
            return BackendStudyGrowthNode(
                studyId: room.id,
                parentStudyId: room.parentStudyId.flatMap { roomsByID[$0]?.id },
                rootStudyId: rootID(room),
                topic: room.topic,
                sortOrder: room.sortOrder,
                depth: depth(room),
                childCount: childrenByParent[room.id]?.count ?? 0,
                activeForQuestions: room.activeForQuestions,
                currentLevel: value.currentLevel,
                previousLevel: value.previousLevel,
                growth: value.growth,
                answerCount: value.records.count,
                measuredTopicCount: value.measuredTopicCount,
                totalTopicCount: value.totalTopicCount,
                latestAt: value.records.last.map { $0.answeredAt ?? $0.question.createdAt },
                trend: value.trend
            )
        }
        let nodesByID = Dictionary(uniqueKeysWithValues: nodeResponses.map { ($0.studyId, $0) })
        let rootResponses = rooms
            .filter { $0.parentStudyId == nil || roomsByID[$0.parentStudyId ?? -1] == nil }
            .sorted {
                if $0.sortOrder != $1.sortOrder {
                    return $0.sortOrder < $1.sortOrder
                }
                return $0.id < $1.id
            }
            .compactMap { room -> BackendStudyGrowthRoot? in
                guard let node = nodesByID[room.id] else {
                    return nil
                }
                return BackendStudyGrowthRoot(
                    studyId: node.studyId,
                    topic: node.topic,
                    activeForQuestions: node.activeForQuestions,
                    currentLevel: node.currentLevel,
                    previousLevel: node.previousLevel,
                    growth: node.growth,
                    answerCount: node.answerCount,
                    measuredTopicCount: node.measuredTopicCount,
                    totalTopicCount: node.totalTopicCount,
                    trend: node.trend,
                    profile: profile(room)
                )
            }
        return BackendStudyGrowth(
            roots: rootResponses,
            nodes: nodeResponses,
            startAt: startAt,
            endAt: endAt,
            generatedAt: Date()
        )
    }

    private struct LegacyGrowthMetrics {
        var records: [StudyRecord]
        var measuredTopicCount: Int
        var totalTopicCount: Int

        var currentLevel: Double? {
            averageLevel(Array(records.suffix(min(records.count, 5))))
        }

        var previousLevel: Double? {
            guard let currentLevel, let growth else {
                return nil
            }
            return currentLevel - growth
        }

        var growth: Double? {
            guard records.count >= 6 else {
                return nil
            }
            let windowSize = min(5, records.count / 2)
            let previous = Array(records.dropLast(windowSize).suffix(windowSize))
            let recent = Array(records.suffix(windowSize))
            guard let previousLevel = averageLevel(previous),
                  let recentLevel = averageLevel(recent) else {
                return nil
            }
            return recentLevel - previousLevel
        }

        var trend: [Double] {
            guard !records.isEmpty else {
                return []
            }
            let chunkSize = max(1, Int(ceil(Double(records.count) / 6)))
            return stride(from: 0, to: records.count, by: chunkSize).compactMap { start in
                let end = min(start + chunkSize, records.count)
                return averageLevel(Array(records[start..<end]))
            }
        }

        private func averageLevel(_ values: [StudyRecord]) -> Double? {
            guard !values.isEmpty else {
                return nil
            }
            return values.map { record in
                let score = min(max(record.gradingResult?.score ?? 0, 0), 100)
                return min(
                    max(
                        Double(record.difficulty.level) + (Double(score) - 50) / 30,
                        1
                    ),
                    10
                )
            }.reduce(0, +) / Double(values.count)
        }
    }
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
                VStack(spacing: 12) {
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
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(stat.topic)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.tail)

                    HStack(spacing: 8) {
                        Text("\(strings.level) \(stat.levelRange.compactRangeText)")
                        Text("·")
                        Text("\(strings.averageScoreShort) \(stat.average)")
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
        .padding(18)
        .background(Color(.secondarySystemBackground))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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
                            .fill(difficulty == range.level ? Color.green.opacity(0.20) : Color.secondary.opacity(0.14))
                    }
                }

                Capsule()
                    .fill(Color.green.opacity(0.82))
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

                Text(MarkdownContent.plainText(record.question.question))
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
