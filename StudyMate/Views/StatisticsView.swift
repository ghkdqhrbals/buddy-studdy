import SwiftUI
#if os(iOS)
import UIKit
#endif

struct StatisticsView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedRecord: StudyRecord?
    @State private var topicPage = 0
    @State private var selectedActivityYear = Calendar.current.component(.year, from: Date())

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
                LazyVStack(alignment: .leading, spacing: 10) {
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
                            activity: appState.backendStatsActivity,
                            isActivityLoading: appState.isBackendStatsActivityLoading,
                            selectedYear: selectedActivityYear,
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
            .searchSafeRefreshControlOffset()
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .navigationTitle("")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .recordDetailPresentation(selectedRecord: $selectedRecord, strings: strings)
        .onChange(of: selectedActivityYear) {
            resetTopicPaging()
            loadStats()
            loadActivity()
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
    var activity: BackendStatsActivity?
    var isActivityLoading: Bool
    var selectedYear: Int
    var strings: AppStrings

    var body: some View {
        let achievements = StatsAchievementSnapshot(activity: activity, topics: topics, selectedYear: selectedYear, strings: strings)

        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 8) {
                StatsAchievementCard(
                    title: strings.studyStreak,
                    value: achievements.streakValue,
                    caption: achievements.streakCaption
                )
                StatsAchievementCard(
                    title: strings.topicGrowth,
                    value: achievements.growthValue,
                    caption: achievements.growthCaption
                )
                StatsAchievementCard(
                    title: achievements.periodTitle,
                    value: achievements.monthValue,
                    caption: achievements.monthCaption
                )
            }

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
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
        .padding(14)
        .background(Color.secondary.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct StatsAchievementCard: View {
    var title: String
    var value: String
    var caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text(value)
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)

            Text(caption)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.035))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
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

    init(activity: BackendStatsActivity?, topics: [TopicStat], selectedYear: Int, strings: AppStrings) {
        let currentYear = Calendar.current.component(.year, from: Date())
        let isCurrentYear = selectedYear == currentYear
        let streakDays = isCurrentYear ? (activity?.streakDays ?? 0) : Self.longestStreak(from: activity)
        streakValue = strings.streakValue(streakDays)
        streakCaption = isCurrentYear
            ? (streakDays > 0 ? strings.streakKeepGoing : strings.streakStartToday)
            : strings.longestStreak

        if let growth = Self.bestGrowth(from: topics) {
            growthValue = growth.delta >= 0 ? "+\(Self.levelFormatter.string(from: NSNumber(value: growth.delta)) ?? "0")" : Self.levelFormatter.string(from: NSNumber(value: growth.delta)) ?? "0"
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
            return .accentColor
        case 4...5:
            return .accentColor.opacity(0.78)
        case 2...3:
            return .accentColor.opacity(0.54)
        case 1:
            return .accentColor.opacity(0.32)
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

    private static let cellSize: CGFloat = 9
    private static let cellSpacing: CGFloat = 3
    private static let monthLabelHeight: CGFloat = 10
    private static let weekdayLabelWidth: CGFloat = 16
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
