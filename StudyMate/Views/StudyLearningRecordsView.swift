#if os(iOS)
import SwiftUI

/// Shared by the My Studies outline and the tree's existing StudyView route.
/// One visible page, not a second answer screen or an unbounded history list.
struct StudyLearningRecordsSection: View {
    @EnvironmentObject private var appState: AppState
    @StateObject private var model: StudyLearningRecordsViewModel
    @State private var scope: StudyLearningRecordScope = .node
    @State private var pageTask: Task<Void, Never>?

    let studyID: Int
    var allowsSubtree = false

    init(
        studyID: Int,
        allowsSubtree: Bool = false,
        preparedLoader: StudyLearningRecordsLoader? = nil
    ) {
        self.studyID = studyID
        self.allowsSubtree = allowsSubtree
        let matchingLoader = preparedLoader.flatMap { loader in
            loader.context.studyID == studyID && loader.context.scope == .node ? loader : nil
        }
        _model = StateObject(wrappedValue: StudyLearningRecordsViewModel(preparedLoader: matchingLoader))
    }

    private var strings: AppStrings { appState.strings }
    private var currentContext: StudyLearningRecordsContext? {
        appState.studyLearningRecordsIdentity.map {
            StudyLearningRecordsContext(identity: $0, studyID: studyID, scope: scope)
        }
    }
    private var page: BackendStudyLearningRecordsPage? {
        guard currentContext != nil, currentContext == model.context else { return nil }
        return model.page
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(strings.studyLearningRecordsTitle)
                    .font(.headline)
                Spacer(minLength: 8)
                if allowsSubtree {
                    Menu {
                        Picker(strings.studyLearningRecordsTitle, selection: $scope) {
                            Text(strings.studyLearningNodeScope).tag(StudyLearningRecordScope.node)
                            Text(strings.studyLearningSubtreeScope).tag(StudyLearningRecordScope.subtree)
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(scope == .node ? strings.studyLearningNodeScope : strings.studyLearningSubtreeScope)
                            Image(systemName: "chevron.down").font(.caption2)
                        }
                        .font(.caption)
                        .frame(minHeight: 44)
                    }
                    .accessibilityIdentifier("studyLearningRecords.scope")
                }
                Button { load(.first) } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.subheadline)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(strings.retry)
                .disabled(model.isLoading || currentContext == nil)
            }

            if currentContext == nil {
                Text(strings.studyLearningSignIn)
                    .font(.caption).foregroundStyle(.secondary)
            } else if let page, let loader = model.detailLoader, loader.context == currentContext {
                if page.items.isEmpty {
                    Text(strings.studyLearningEmpty)
                        .font(.caption).foregroundStyle(.secondary)
                        .padding(.vertical, 8)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(page.items) { item in
                            NavigationLink {
                                if let record = item.commonRecord {
                                    CommonStudyRecordDetailView(record: record)
                                } else {
                                    StudyLearningRecordDetailView(record: item, loader: loader)
                                }
                            } label: {
                                StudyLearningRecordRow(record: item, strings: strings, showsTopic: scope == .subtree)
                            }
                            .buttonStyle(.plain)
                            if item.id != page.items.last?.id { Divider() }
                        }
                    }
                }
                pagination
            } else if model.isLoading || model.context != currentContext {
                ProgressView().controlSize(.small).frame(maxWidth: .infinity, minHeight: 44)
            }

            if model.context == currentContext, model.failed {
                HStack(spacing: 8) {
                    Text(strings.studyLearningLoadFailed)
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 4)
                    Button(strings.retry) { load(.retry) }
                        .font(.caption.weight(.semibold))
                        .frame(minHeight: 44)
                }
            }
        }
        .task(id: currentContext) {
            pageTask?.cancel()
            guard let loader = appState.makeStudyLearningRecordsLoader(studyID: studyID, scope: scope) else {
                model.deactivate()
                return
            }
            await model.activate(loader)
        }
        .onDisappear {
            pageTask?.cancel()
            model.deactivate()
        }
        .onChange(of: allowsSubtree) { _, allowsSubtree in
            if !allowsSubtree { scope = .node }
        }
        .accessibilityIdentifier("studyLearningRecords.section")
    }

    private var pagination: some View {
        HStack(spacing: 12) {
            Button(strings.studyLearningPrevious) { load(.previous) }
                .disabled(!model.canGoPrevious)
                .frame(minHeight: 44)
            Spacer(minLength: 4)
            if model.isLoading {
                ProgressView().controlSize(.small)
            } else {
                Text(strings.studyLearningPageNumber(model.pageNumber))
                    .foregroundStyle(.secondary).monospacedDigit()
            }
            Spacer(minLength: 4)
            Button(strings.studyLearningNext) { load(.next) }
                .disabled(!model.canGoNext)
                .frame(minHeight: 44)
        }
        .font(.caption)
    }

    private func load(_ direction: StudyLearningRecordsViewModel.Direction) {
        pageTask?.cancel()
        pageTask = Task { await model.load(direction) }
    }
}

private struct StudyLearningRecordRow: View {
    let record: BackendStudyLearningRecord
    let strings: AppStrings
    var showsTopic: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: record.source == .voiceTutor ? "waveform" : "text.bubble")
                .font(.subheadline)
                .foregroundStyle(record.source == .voiceTutor ? Color.accentColor : Color.secondary)
                .frame(width: 20)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(record.source == .voiceTutor ? strings.studyLearningVoiceLabel : strings.studyLearningQuestionLabel)
                    if showsTopic { Text(record.topic).lineLimit(1) }
                    Spacer(minLength: 4)
                    if let score = record.score { Text("\(score)/100").monospacedDigit() }
                }
                .font(.caption2).foregroundStyle(.secondary)
                Text(record.question)
                    .font(.subheadline).foregroundStyle(.primary)
                    .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                if let answer = record.answer, !answer.isEmpty {
                    Text(answer).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack {
                    Text(record.createdAt, format: .dateTime.month().day().hour().minute())
                    if record.translationPending { Text(strings.studyLearningTranslationPending) }
                }
                .font(.caption2).foregroundStyle(.secondary)
            }
            Image(systemName: "chevron.right")
                .font(.caption2).foregroundStyle(.tertiary)
                .padding(.top, 5)
        }
        .padding(.vertical, 11)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private struct StudyLearningRecordDetailView: View {
    @EnvironmentObject private var appState: AppState
    @StateObject private var model: StudyLearningRecordDetailViewModel
    @State private var readTask: Task<Void, Never>?
    let loader: StudyLearningRecordsLoader

    init(record: BackendStudyLearningRecord, loader: StudyLearningRecordsLoader) {
        self.loader = loader
        _model = StateObject(wrappedValue: StudyLearningRecordDetailViewModel(record: record))
    }

    private var strings: AppStrings { appState.strings }
    private var isCurrent: Bool {
        loader.isCurrent() && appState.studyLearningRecordsIdentity == loader.context.identity
    }

    var body: some View {
        Group {
            if isCurrent, let commonRecord = model.record.commonRecord {
                CommonStudyRecordDetailView(record: commonRecord)
            } else {
                legacyDetail
            }
        }
        .task {
            await model.load(using: loader, view: model.isShowingOriginal ? .original : .localized)
        }
        .onDisappear {
            readTask?.cancel()
            model.deactivate()
        }
        .accessibilityIdentifier("studyLearningRecords.detail")
    }

    private var legacyDetail: some View {
        ScrollView {
            if isCurrent {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 8) {
                        Label(
                            model.record.source == .voiceTutor ? strings.studyLearningVoiceLabel : strings.studyLearningQuestionLabel,
                            systemImage: model.record.source == .voiceTutor ? "waveform" : "text.bubble"
                        )
                        Spacer(minLength: 8)
                        if let score = model.record.score { Text("\(score)/100").monospacedDigit() }
                    }
                    .font(.caption).foregroundStyle(.secondary)

                    HStack(spacing: 8) {
                        if model.isLoading { ProgressView().controlSize(.small) }
                        if model.record.translationPending && !model.isShowingOriginal {
                            Text(strings.studyLearningTranslationPending)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        Button(model.isShowingOriginal ? strings.studyLearningLocalized : strings.studyLearningOriginal) {
                            load(model.isShowingOriginal ? .localized : .original)
                        }
                        .font(.caption.weight(.medium)).frame(minHeight: 44)
                    }

                    if model.failed {
                        HStack {
                            Text(strings.studyLearningLoadFailed).foregroundStyle(.secondary)
                            Spacer(minLength: 8)
                            Button(strings.retry) { load(model.isShowingOriginal ? .original : .localized) }
                                .frame(minHeight: 44)
                        }
                        .font(.caption)
                    }
                    detailContent
                }
                .padding(16)
            } else {
                Text(strings.studyLearningReopen).font(.caption).foregroundStyle(.secondary).padding(16)
            }
        }
        .navigationTitle(isCurrent ? model.record.topic : strings.studyLearningRecordsTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var detailContent: some View {
        let learnerQuestion = model.record.voiceRecord?.kind == .learnerQuestion
        detailText(learnerQuestion ? strings.studyLearningLearnerQuestion : strings.studyLearningTeacherQuestion, model.record.question)
        detailText(
            learnerQuestion ? strings.studyLearningTutorAnswer : strings.studyLearningLearnerAnswer,
            nonempty(model.record.answer) ?? strings.studyLearningNoAnswer
        )
        if let voice = model.record.voiceRecord {
            if let feedback = nonempty(voice.feedback) { detailText(strings.studyLearningFeedback, feedback) }
            detailList(strings.studyLearningStrengths, voice.strengths)
            detailList(strings.studyLearningImprovements, voice.improvements)
            if let depth = nonempty(voice.depthSummary) {
                DisclosureGroup(strings.studyLearningDepth) {
                    MarkdownMessageText(markdown: depth)
                        .font(.subheadline).textSelection(.enabled).padding(.top, 8)
                }
                .font(.subheadline)
            }
        } else if let grading = model.record.questionRecord?.gradingResult {
            if let feedback = nonempty(grading.feedback) { detailText(strings.studyLearningFeedback, feedback) }
            if let explanation = nonempty(grading.explanation) { detailText(strings.explanation, explanation) }
        }
    }

    private func detailText(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            MarkdownMessageText(markdown: body).font(.body).textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func detailList(_ title: String, _ values: [String]) -> some View {
        let values = values.compactMap { nonempty($0) }
        if !values.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                    HStack(alignment: .top, spacing: 6) {
                        Text("•").foregroundStyle(.secondary)
                        MarkdownMessageText(markdown: value).font(.subheadline).textSelection(.enabled)
                    }
                }
            }
        }
    }

    private func nonempty(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }

    private func load(_ view: LocalizedContentView) {
        readTask?.cancel()
        readTask = Task { await model.load(using: loader, view: view) }
    }
}
#endif
