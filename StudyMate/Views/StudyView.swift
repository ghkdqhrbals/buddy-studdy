import SwiftUI
#if os(iOS)
import MarkdownUI
#endif

struct StudyView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    let preferredCategoryID: String?
    let isContentPrepared: Bool
    @State private var showsHint = false
    @State private var draftAnswer = ""
    @State private var showsPendingLimitHelp = false
    @State private var editingStudyRoom: BackendStudyRoom?
    @State private var showsCustomQuestionComposer = false
    @State private var savedCustomQuestion: StudyRecord?
    @State private var selectedTreeRootID: Int?
    @State private var answerSubmissionTask: Task<Void, Never>?
    @State private var answerGradingOwnerID: String?
    @State private var isResolvingInitialAnswerState: Bool
    #if os(iOS)
    @FocusState private var isAnswerEditorFocused: Bool
    @Environment(\.scenePhase) private var reviewScenePhase
    @State private var isReviewScreenVisible = false
    #endif

    init(
        preferredCategoryID: String? = nil,
        isContentPrepared: Bool = false,
        initialAnswerDraft: String = ""
    ) {
        self.preferredCategoryID = preferredCategoryID
        self.isContentPrepared = isContentPrepared
        _draftAnswer = State(initialValue: initialAnswerDraft)
        _isResolvingInitialAnswerState = State(initialValue: !isContentPrepared)
    }

    var body: some View {
        let strings = appState.strings

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                StudySettingsSummarySection(
                    topic: studyTopicLabel(strings: strings),
                    level: selectedDifficulty.displayName(language: appState.settings.appLanguage),
                    strings: strings
                )

                if let notice = appState.questionQuotaNotice {
                    questionQuotaNoticeView(notice, strings: strings)
                }

                Divider()

                if selectedStudyRecord != nil,
                   let notificationLandingMessage = appState.notificationLandingMessage {
                    notificationLandingInlineView(message: notificationLandingMessage, strings: strings)
                }

                Group {
                    if let record = selectedStudyRecord {
                        let isGradingAnswer = appState.isAnswerGradingInProgress(for: record)
                        StudyThreadHistorySection(
                            records: appState.studyThread(containing: record).filter { $0.followUpDepth < record.followUpDepth },
                            strings: strings
                        )
                        if record.isFollowUp {
                            Text(strings.followUpTurn(record.followUpDepth))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                        if record.isCustomQuestion {
                            CustomQuestionConversation(record: record, strings: strings)
                        } else if record.isFollowUp && record.questionStatus == .skipped {
                            SkippedFollowUpConversation(record: record, strings: strings)
                        } else {
                            StudyConversationSection(
                            question: record.question,
                            draftAnswer: $draftAnswer,
                            showsHint: $showsHint,
                            submittedAnswer: StudyAnswerPresentationPolicy.submittedAnswer(for: record),
                            gradingResult: record.gradingResult,
                            isGradingAnswer: isGradingAnswer,
                            isResolvingAnswerState: isResolvingInitialAnswerState,
                            gradingStatusMessage: appState.gradingPresentationMessage(for: record),
                            canSubmitAnswer: canSubmitAnswer,
                            allowsAnswerEditing: StudyAnswerPresentationPolicy.shouldShowEditor(for: record),
                            terminalStatusMessage: record.questionStatus == .skipped
                                ? strings.questionSkippedStatus
                                : ((record.questionStatus == .completed || record.questionStatus == .graded)
                                    && record.gradingResult == nil ? strings.questionCompletedStatus : nil),
                            strings: strings,
                            answerEditor: {
                                answerEditor()
                            },
                            onSubmit: submitCurrentAnswer,
                            onSkip: {
                                appState.skipStudyRoomRecord(record)
                            }
                            )
                            StudyFollowUpActions(record: record)
                        }
                    } else if appState.isGeneratingQuestion(categoryID: targetCategoryID) {
                        questionLoadingMessage(strings: strings)
                            .padding(.top, 4)
                    } else {
                        noQuestionView(strings: strings)
                            .frame(maxWidth: .infinity, minHeight: 140)
                    }
                }

                #if os(iOS)
                if let room = appState.backendStudyRoom(categoryID: preferredCategoryID) {
                    Divider().padding(.top, 8)
                    StudyLearningRecordsSection(
                        studyID: room.id,
                        allowsSubtree: room.parentStudyId == nil || appState.backendStudyRooms.contains { $0.parentStudyId == room.id },
                        preparedLoader: isContentPrepared
                            ? appState.makeStudyLearningRecordsLoader(studyID: room.id, scope: .node)
                            : nil
                    )
                    .id(room.id)
                }
                #endif
            }
            .padding(.top, 10)
            .padding(.trailing, 8)
            .padding(.bottom, 22)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        #if os(iOS)
        .scrollDismissesKeyboard(.interactively)
        #endif
        .refreshable {
            await appState.refreshVisibleData()
        }
        .toolbar {
            #if os(iOS)
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarTrailing) {
                    toolbarActions(strings: strings)
                }
                .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .topBarTrailing) {
                    toolbarActions(strings: strings)
                }
            }
            #endif
        }
        .navigationDestination(item: $selectedTreeRootID) { rootStudyID in
            MobileStudyTreeView(rootStudyID: rootStudyID)
        }
        .sheet(item: $editingStudyRoom) { room in
            StudyEditorSheet(
                navigationTitle: strings.editStudyCategory,
                initialTitle: room.topic,
                initialDifficulty: Difficulty(level: room.difficultyLevel),
                initialQuestionRotationEnabled: room.activeForQuestions,
                strings: strings,
                onDelete: {
                    deleteStudyRoom(room)
                }
            ) { title, difficulty, questionRotationEnabled in
                appState.updateStudyTreeCategory(
                    roomID: room.id,
                    title: title,
                    difficulty: difficulty
                )
                if let questionRotationEnabled,
                   questionRotationEnabled != room.activeForQuestions {
                    appState.setStudyTopicActive(
                        studyID: room.id,
                        active: questionRotationEnabled
                    )
                }
            }
        }
        .sheet(isPresented: $showsCustomQuestionComposer) {
            if let room = selectedBackendStudyRoom {
                CustomQuestionComposer(studyID: room.id) { record in
                    savedCustomQuestion = record
                }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { savedCustomQuestion != nil },
            set: { if !$0 { savedCustomQuestion = nil } }
        )) {
            if let record = savedCustomQuestion {
                StudyRecordDetailView(record: record, refreshesRecordOnAppear: false)
                    .padding(.horizontal, 16)
                    .navigationTitle(strings.customQuestionTag)
            }
        }
        .alert(strings.pendingQuestionLimitTitle, isPresented: $showsPendingLimitHelp) {
            Button(strings.done, role: .cancel) {}
        } message: {
            Text(strings.pendingQuestionLimitMessage)
        }
        .onAppear {
            #if os(iOS)
            isReviewScreenVisible = true
            #endif
            draftAnswer = appState.answerDraft(for: selectedStudyRecord)
            presentPendingLimitNoticeIfNeeded()
        }
        .task(id: preferredCategoryID) {
            let ownerID = UUID().uuidString
            answerGradingOwnerID = ownerID
            if isContentPrepared {
                isResolvingInitialAnswerState = false
                await appState.prepareStudyRoom(
                    categoryID: preferredCategoryID,
                    gradingPollingOwnerID: ownerID,
                    onInitialStateResolved: {
                        isResolvingInitialAnswerState = false
                    },
                    shouldRefreshDetail: false
                )
            } else {
                isResolvingInitialAnswerState = true
                async let roomPreparation: Void = appState.prepareStudyRoom(
                    categoryID: preferredCategoryID,
                    gradingPollingOwnerID: ownerID,
                    onInitialStateResolved: {
                        isResolvingInitialAnswerState = false
                    }
                )
                async let quotaRefresh: Void = appState.refreshQuestionQuota()
                _ = await (roomPreparation, quotaRefresh)
            }
            isResolvingInitialAnswerState = false
            if answerGradingOwnerID == ownerID {
                answerGradingOwnerID = nil
            }
        }
        .onDisappear {
            #if os(iOS)
            isReviewScreenVisible = false
            #endif
            if let answerGradingOwnerID {
                appState.cancelAnswerGradingPolling(
                    ownerID: answerGradingOwnerID,
                    reason: "study-view-disappeared"
                )
                self.answerGradingOwnerID = nil
            }
            appState.flushPendingAnswerDraftSave()
        }
        .onChange(of: draftAnswer) {
            if let selectedStudyRecord,
               StudyAnswerPresentationPolicy.shouldShowEditor(for: selectedStudyRecord),
               draftAnswer != appState.answerDraft(for: selectedStudyRecord) {
                appState.updateAnswer(draftAnswer, for: selectedStudyRecord)
            }
        }
        .onChange(of: selectedStudyRecord?.id) {
            showsHint = false
            draftAnswer = appState.answerDraft(for: selectedStudyRecord)
        }
        .task(id: selectedStudyRecord?.id) {
            if let record = selectedStudyRecord, !record.isCustomQuestion {
                await appState.loadStudyThread(containing: record)
            }
        }
        .onChange(of: selectedStudyRecord?.answer) {
            if draftAnswer != appState.answerDraft(for: selectedStudyRecord) {
                draftAnswer = appState.answerDraft(for: selectedStudyRecord)
            }
        }
        .onChange(of: appState.pendingQuestionLimitCategoryID) {
            presentPendingLimitNoticeIfNeeded()
        }
        #if os(iOS)
        .onChange(of: StudyReviewCompletion(
            recordID: selectedStudyRecord?.id,
            isGraded: selectedStudyRecord?.gradingResult != nil
        )) { previous, current in
            if current.isNewCompletion(after: previous), appState.isCommunitySessionActive,
               isReviewScreenVisible, reviewScenePhase == .active,
               !isResolvingInitialAnswerState, editingStudyRoom == nil, selectedTreeRootID == nil {
                StudyReviewCoordinator.shared.recordLearningCompletion()
            }
        }
        #endif
    }

    private func questionLoadingMessage(strings: AppStrings) -> some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.green.opacity(0.16))

                ProgressView()
                    .controlSize(.small)
                    .tint(.green)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text(strings.fetchingQuestion)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(strings.fetchingQuestionDescription)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 14)
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.green.opacity(0.09))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.green.opacity(0.28), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(strings.fetchingQuestion). \(strings.fetchingQuestionDescription)")
    }

    private var canSubmitAnswer: Bool {
        StudyAnswerPresentationPolicy.shouldShowEditor(for: selectedStudyRecord) &&
            !isResolvingInitialAnswerState &&
            !draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !appState.isAnswerGradingInProgress(for: selectedStudyRecord)
    }

    private var selectedStudyRecord: StudyRecord? {
        guard let record = appState.studyRoomRecordForDisplay(categoryID: preferredCategoryID) else { return nil }
        guard record.isCompleted, !record.isCustomQuestion else { return record }
        return appState.studyThread(containing: record).last ?? record
    }

    private var selectedDifficulty: Difficulty {
        if let room = appState.backendStudyRoom(categoryID: preferredCategoryID) {
            return Difficulty(level: room.difficultyLevel)
        }

        if let preferredCategoryID,
           let category = appState.settings.category(for: preferredCategoryID) {
            return category.difficulty
        }

        return appState.settings.difficulty
    }

    private var selectedCategory: StudyCategory? {
        guard let preferredCategoryID else {
            return appState.settings.category(for: appState.settings.selectedStudyCategoryID)
        }

        return appState.settings.category(for: preferredCategoryID)
    }

    private func studyTopicLabel(strings: AppStrings) -> String {
        let topic = selectedTopic.trimmingCharacters(in: .whitespacesAndNewlines)
        return topic.isEmpty ? strings.studyFallback : topic
    }

    private var selectedTopic: String {
        if let room = appState.backendStudyRoom(categoryID: preferredCategoryID) {
            return room.topic
        }

        if let preferredCategoryID,
           let category = appState.settings.category(for: preferredCategoryID) {
            return category.title
        }

        let topic = appState.settings.topic.trimmingCharacters(in: .whitespacesAndNewlines)
        return topic.isEmpty ? appState.strings.studyFallback : topic
    }

    private func normalizedTopicKey(_ topic: String) -> String {
        topic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined()
    }

    @ViewBuilder
    private func noQuestionView(strings: AppStrings) -> some View {
        if let notificationLandingMessage = appState.notificationLandingMessage {
            VStack(spacing: 12) {
                ContentUnavailableView(
                    strings.notificationQuestionMissingTitle,
                    systemImage: "bell.slash",
                    description: Text(notificationLandingMessage)
                )

                Text(strings.notificationQuestionUnavailableHelp)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                EmptyView()
            }
            .frame(maxWidth: .infinity)
        } else {
            ContentUnavailableView(
                strings.noQuestion,
                systemImage: "questionmark.bubble",
                description: Text(strings.noQuestionDescription)
            )
        }
    }

    private func toolbarActions(strings: AppStrings) -> some View {
        HStack(spacing: 8) {
            toolbarNewQuestionButton(strings: strings)
            studyOptionsMenu(strings: strings)
        }
        .fixedSize()
    }

    private func toolbarNewQuestionButton(strings: AppStrings) -> some View {
        Button {
            requestNewQuestion()
        } label: {
            #if os(iOS)
            MobileToolbarIconButtonLabel(systemName: "plus")
            #else
            Image(systemName: "plus")
            #endif
        }
        .buttonStyle(.plain)
        .disabled(appState.isGeneratingQuestion)
        .opacity(appState.isGeneratingQuestion || hasReachedPendingQuestionLimit ? 0.55 : 1)
        .accessibilityLabel(appState.isGeneratingQuestion ? strings.fetchingQuestion : strings.newQuestion)
        .accessibilityHint(hasReachedPendingQuestionLimit ? strings.pendingQuestionLimitMessage : "")
    }

    private func studyOptionsMenu(strings: AppStrings) -> some View {
        Menu {
            if let room = selectedBackendStudyRoom {
                Button {
                    showsCustomQuestionComposer = true
                } label: {
                    Label(strings.createCustomQuestion, systemImage: "square.and.pencil")
                }

                Divider()
                Button {
                    editingStudyRoom = room
                } label: {
                    Label(strings.editStudyCategory, systemImage: "pencil")
                }

                Button {
                    selectedTreeRootID = appState.rootStudyRoom(for: room.id)?.id ?? room.id
                } label: {
                    Label(
                        strings.viewFullStudyTree,
                        systemImage: "point.3.connected.trianglepath.dotted"
                    )
                }

            }
        } label: {
            #if os(iOS)
            MobileToolbarIconButtonLabel(systemName: "ellipsis")
            #else
            Image(systemName: "ellipsis")
            #endif
        }
        .buttonStyle(.plain)
        .disabled(selectedBackendStudyRoom == nil)
        .accessibilityLabel(strings.more)
    }

    private var selectedBackendStudyRoom: BackendStudyRoom? {
        appState.backendStudyRoom(categoryID: targetCategoryID)
    }

    private func deleteStudyRoom(_ room: BackendStudyRoom) {
        appState.deleteStudyCategory(id: String(room.id))
        dismiss()
    }

    private func requestNewQuestion() {
        guard !appState.isGeneratingQuestion else {
            return
        }

        if hasReachedPendingQuestionLimit {
            showsPendingLimitHelp = true
            return
        }

        Task {
            await appState.generateQuestion(studyCategoryID: targetCategoryID)
        }
    }

    private var hasReachedPendingQuestionLimit: Bool {
        appState.hasReachedPendingQuestionLimit(categoryID: targetCategoryID)
    }

    private var targetCategoryID: String? {
        preferredCategoryID ?? selectedCategory?.id
    }

    private func presentPendingLimitNoticeIfNeeded() {
        guard appState.pendingQuestionLimitCategoryID == targetCategoryID else {
            return
        }
        showsPendingLimitHelp = true
        appState.clearPendingQuestionLimitNotice(categoryID: targetCategoryID)
    }

    private func questionQuotaNoticeView(_ message: String, strings: AppStrings) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(strings.monthlyQuotaReached)
                    .font(.subheadline.weight(.semibold))
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Button(strings.done) {
                appState.clearQuestionQuotaNotice()
            }
            .font(.caption.weight(.semibold))
        }
        .padding(12)
        .background(Color.orange.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func notificationLandingInlineView(message: String, strings: AppStrings) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "bell.slash")
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 3) {
                Text(strings.notificationQuestionMissingTitle)
                    .font(.subheadline.weight(.semibold))
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            Button(strings.done) {
                appState.clearStatus()
            }
            .buttonStyle(.borderless)
            .font(.caption)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.07))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func answerEditor() -> some View {
        #if os(iOS)
        AnswerEditor(
            text: $draftAnswer,
            placeholder: appState.strings.answerPlaceholder,
            minHeight: 96,
            isFocused: $isAnswerEditorFocused
        )
        #else
        AnswerEditor(
            text: $draftAnswer,
            placeholder: appState.strings.answerPlaceholder,
            minHeight: 96
        )
        #endif
    }

    private func submitCurrentAnswer() {
        guard let selectedStudyRecord,
              canSubmitAnswer,
              answerSubmissionTask == nil else {
            return
        }

        #if os(iOS)
        isAnswerEditorFocused = false
        #endif

        let ownerID = UUID().uuidString
        answerGradingOwnerID = ownerID
        answerSubmissionTask = Task {
            await appState.gradeStudyRoomRecord(
                selectedStudyRecord,
                answer: draftAnswer,
                pollingOwnerID: ownerID
            )
            answerSubmissionTask = nil
            guard answerGradingOwnerID == ownerID else {
                return
            }
            answerGradingOwnerID = nil
        }
    }
}

struct MarkdownMessageText: View {
    var markdown: String
    var fillsWidth = true

    var body: some View {
        #if os(iOS)
        MarkdownUI.Markdown(markdown)
            .markdownImageProvider(.asset)
            .environment(
                \.openURL,
                OpenURLAction { url in
                    guard ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
                        return .discarded
                    }
                    return .systemAction
                }
            )
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: fillsWidth ? .infinity : nil, alignment: .leading)
        #else
        Text(MarkdownContent.attributedString(markdown))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: fillsWidth ? .infinity : nil, alignment: .leading)
        #endif
    }
}

enum ConversationBubblePalette {
    static var incomingBackground: Color {
        #if os(iOS)
        Color(uiColor: .systemGray5)
        #elseif os(macOS)
        Color(nsColor: .controlBackgroundColor)
        #else
        Color.secondary.opacity(0.14)
        #endif
    }

    static let incomingBorder = Color.clear
}

struct CompactMessageLayout: Layout {
    var minimumWidth: CGFloat = 44
    var maximumWidth: CGFloat = 280

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        guard let subview = subviews.first else {
            return .zero
        }

        let availableWidth = max(0, min(proposal.width ?? maximumWidth, maximumWidth))
        let intrinsicSize = subview.sizeThatFits(.unspecified)
        let resolvedWidth = min(
            max(intrinsicSize.width, minimumWidth),
            availableWidth
        )
        let resolvedSize = subview.sizeThatFits(
            ProposedViewSize(width: resolvedWidth, height: proposal.height)
        )
        return CGSize(width: resolvedWidth, height: resolvedSize.height)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        guard let subview = subviews.first else {
            return
        }

        subview.place(
            at: bounds.origin,
            anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: bounds.height)
        )
    }
}

private struct StudyConversationSection<AnswerEditorContent: View>: View {
    var question: QuestionItem
    @Binding var draftAnswer: String
    @Binding var showsHint: Bool
    var submittedAnswer: String?
    var gradingResult: GradingResult?
    var isGradingAnswer: Bool
    var isResolvingAnswerState: Bool
    var gradingStatusMessage: String?
    var canSubmitAnswer: Bool
    var allowsAnswerEditing: Bool
    var terminalStatusMessage: String?
    var strings: AppStrings
    @ViewBuilder var answerEditor: () -> AnswerEditorContent
    var onSubmit: () -> Void
    var onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            StudyChatBubble(role: .tutor) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 10) {
                        MarkdownMessageText(markdown: question.question)
                            .font(.body)
                            .foregroundStyle(.primary)
                            .tint(.accentColor)
                            .textSelection(.enabled)

                        ZStack {
                            if allowsAnswerEditing && submittedAnswer == nil &&
                                gradingResult == nil &&
                                !isGradingAnswer &&
                                !isResolvingAnswerState {
                                Button {
                                    onSkip()
                                } label: {
                                    Image(systemName: "forward.fill")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(.secondary)
                                        .frame(width: 30, height: 30)
                                        .background(Color.secondary.opacity(0.12), in: Circle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(strings.skipQuestion)
                            }
                        }
                        .frame(width: 30, height: 30)
                    }

                    hintView
                }
            }

            if !isResolvingAnswerState,
               let displayedLearnerAnswer {
                StudyChatBubble(role: .learnerAnswer) {
                    MarkdownMessageText(markdown: displayedLearnerAnswer, fillsWidth: false)
                        .font(.body)
                        .foregroundStyle(.white)
                        .tint(.white)
                        .textSelection(.enabled)
                        .multilineTextAlignment(.leading)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 13)
                        .background(Color.green.opacity(0.92), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            } else if allowsAnswerEditing && !isResolvingAnswerState &&
                        gradingResult == nil &&
                        !isGradingAnswer {
                StudyChatBubble(role: .learnerInput) {
                    MessageAnswerInput(
                        strings: strings,
                        isGradingAnswer: isGradingAnswer,
                        canSubmitAnswer: canSubmitAnswer,
                        answerEditor: answerEditor,
                        onSubmit: onSubmit
                    )
                }
            }

            if !isResolvingAnswerState, let terminalStatusMessage {
                Text(terminalStatusMessage)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if !isResolvingAnswerState,
               isGradingAnswer,
               let gradingStatusMessage {
                StudyChatBubble(role: .feedback) {
                    HStack(spacing: 10) {
                        ProgressView()
                            .controlSize(.small)

                        Text(gradingStatusMessage)
                            .font(.subheadline.weight(.medium))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(gradingStatusMessage)
            }

            if let gradingResult {
                StudyChatBubble(role: .feedback) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Label(gradingResult.gradeTitle(strings: strings), systemImage: gradingResult.gradeIconName)
                            Spacer(minLength: 12)
                            Text("\(gradingResult.score)/100")
                                .font(.headline)
                        }

                        MarkdownMessageText(markdown: gradingResult.feedback)
                            .font(.body)

                        MarkdownMessageText(markdown: gradingResult.explanation)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var displayedLearnerAnswer: String? {
        if let submittedAnswer {
            return submittedAnswer
        }
        let trimmedDraft = draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
        return isGradingAnswer && !trimmedDraft.isEmpty ? draftAnswer : nil
    }

    @ViewBuilder
    private var hintView: some View {
        if let hint = question.expectedAnswerHint,
           !hint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    showsHint.toggle()
                } label: {
                    Label(showsHint ? strings.hideHint : strings.showHint, systemImage: "lightbulb")
                }
                .buttonStyle(.borderless)
                .font(.caption)
                .foregroundStyle(.secondary)
                .tint(.accentColor)

                if showsHint {
                    MarkdownMessageText(markdown: hint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .tint(.accentColor)
                        .textSelection(.enabled)
                        .lineLimit(nil)
                }
            }
            .padding(.top, 4)
        }
    }
}

private enum StudyChatBubbleRole: Equatable {
    case tutor
    case learnerInput
    case learnerAnswer
    case feedback

    var frameAlignment: Alignment {
        switch self {
        case .tutor, .feedback:
            .leading
        case .learnerInput, .learnerAnswer:
            .trailing
        }
    }

    var bubbleColor: Color {
        switch self {
        case .tutor:
            ConversationBubblePalette.incomingBackground
        case .learnerInput, .learnerAnswer:
            Color.clear
        case .feedback:
            ConversationBubblePalette.incomingBackground
        }
    }

    var borderColor: Color {
        switch self {
        case .tutor:
            ConversationBubblePalette.incomingBorder
        case .learnerInput, .learnerAnswer:
            Color.clear
        case .feedback:
            ConversationBubblePalette.incomingBorder
        }
    }
}

private struct StudyChatBubble<Content: View>: View {
    var role: StudyChatBubbleRole
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if role == .learnerInput || role == .learnerAnswer {
                Spacer(minLength: 34)
            }

            if role == .learnerInput {
                content()
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if role == .learnerAnswer {
                CompactMessageLayout {
                    content()
                }
            } else {
                content()
                    .padding(.vertical, 11)
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(role.bubbleColor)
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(role.borderColor, lineWidth: 1)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            }

            if role != .learnerInput && role != .learnerAnswer {
                Spacer(minLength: 34)
            }
        }
        .frame(maxWidth: .infinity, alignment: role.frameAlignment)
    }
}

private struct MessageAnswerInput<AnswerEditorContent: View>: View {
    var strings: AppStrings
    var isGradingAnswer: Bool
    var canSubmitAnswer: Bool
    @ViewBuilder var answerEditor: () -> AnswerEditorContent
    var onSubmit: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            answerEditor()

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
            .accessibilityLabel(strings.send)
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

private struct StudySettingsSummarySection: View {
    var topic: String
    var level: String
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(strings.studySettings)
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                StudySummaryMetric(title: strings.studyTopicShort, value: topic)
                StudySummaryMetric(title: strings.studyLevelShort, value: level)
            }
        }
    }
}

private struct StudySummaryMetric: View {
    var title: String
    var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 6)
        .padding(.horizontal, 9)
        .background(Color.secondary.opacity(0.035))
        .overlay {
            RoundedRectangle(cornerRadius: 7)
                .stroke(Color.secondary.opacity(0.08), lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 7))
    }
}

private struct AnswerEditor: View {
    @Binding var text: String
    var placeholder: String
    var minHeight: CGFloat
    #if os(iOS)
    var isFocused: FocusState<Bool>.Binding
    #endif

    var body: some View {
        let editor = TextField(placeholder, text: $text, axis: .vertical)
            .font(.body)
            .textFieldStyle(.plain)
            .lineLimit(1...5)
            .frame(minHeight: 32, alignment: .center)

        #if os(iOS)
        editor
            .focused(isFocused)
        #else
        editor
        #endif
    }

}

extension GradingResult {
    func gradeTitle(strings: AppStrings) -> String {
        switch score {
        case 90...100:
            strings.correct
        case 70..<90:
            strings.nearlyCorrect
        case 40..<70:
            strings.partialCorrect
        default:
            strings.needsImprovement
        }
    }

    var gradeIconName: String {
        switch score {
        case 70...100:
            "checkmark.circle.fill"
        case 40..<70:
            "exclamationmark.circle.fill"
        default:
            "xmark.circle.fill"
        }
    }
}

struct StudyThreadHistorySection: View {
    var records: [StudyRecord]
    var strings: AppStrings

    var body: some View {
        ForEach(records) { record in
            VStack(alignment: .leading, spacing: 12) {
                Text(record.isFollowUp ? strings.followUpTurn(record.followUpDepth) : strings.originalQuestion)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                StudyConversationSection(
                    question: record.question,
                    draftAnswer: .constant(record.answer ?? ""),
                    showsHint: .constant(false),
                    submittedAnswer: record.answer,
                    gradingResult: record.gradingResult,
                    isGradingAnswer: false,
                    isResolvingAnswerState: false,
                    gradingStatusMessage: nil,
                    canSubmitAnswer: false,
                    allowsAnswerEditing: false,
                    terminalStatusMessage: record.questionStatus == .skipped ? strings.skippedFollowUp : nil,
                    strings: strings,
                    answerEditor: { EmptyView() },
                    onSubmit: {},
                    onSkip: {}
                )
                Divider().padding(.vertical, 6)
            }
        }
    }
}

struct StudyFollowUpActions: View {
    @EnvironmentObject private var appState: AppState
    var record: StudyRecord

    var body: some View {
        let strings = appState.strings
        if record.isQuestion && !record.isDetachedLocalQuestion && record.gradingResult != nil && !record.isCustomQuestion {
            VStack(alignment: .leading, spacing: 10) {
                if let message = appState.studyThreadErrors[record.threadRootID] {
                    HStack {
                        Text(message).font(.caption).foregroundStyle(.secondary)
                        Button(strings.retry) {
                            Task { await appState.loadStudyThread(containing: record) }
                        }
                        .font(.caption.weight(.semibold))
                    }
                }
                if let message = appState.followUpErrors[record.threadRootID] {
                    Text(message).font(.caption).foregroundStyle(.red)
                }
                if appState.isGeneratingFollowUp(for: record) {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(strings.fetchingQuestion).font(.subheadline)
                    }
                    .accessibilityElement(children: .combine)
                } else if record.followUpDepth >= StudyFollowUpPolicy.maximumDepth {
                    Text(strings.followUpLimitReached)
                        .font(.subheadline.weight(.medium))
                } else if StudyFollowUpPolicy.canRequest(after: record, thread: appState.studyThread(containing: record)) {
                    Button {
                        Task { await appState.generateFollowUp(after: record) }
                    } label: {
                        Label(strings.followUpQuestion, systemImage: "arrow.turn.down.right")
                            .font(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .disabled(appState.isGeneratingQuestion || !appState.hasLoadedStudyThread(record))
                    Text(strings.followUpQuotaNotice)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(strings.followUpPracticeNotice)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.leading, 52)
            .padding(.top, 6)
        }
    }
}

struct CustomQuestionConversation: View {
    var record: StudyRecord
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(strings.customQuestionTag)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            StudyChatBubble(role: .tutor) {
                MarkdownMessageText(markdown: record.question.question)
                    .textSelection(.enabled)
            }
            if let answer = record.answer {
                StudyChatBubble(role: .learnerAnswer) {
                    MarkdownMessageText(markdown: answer, fillsWidth: false)
                        .textSelection(.enabled)
                        .padding(12)
                        .background(Color.green.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }
}

struct SkippedFollowUpConversation: View {
    var record: StudyRecord
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            StudyChatBubble(role: .tutor) {
                MarkdownMessageText(markdown: record.question.question)
                    .textSelection(.enabled)
            }
            Label(strings.skippedFollowUp, systemImage: "forward.fill")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.leading, 52)
        }
    }
}

struct CustomQuestionComposer: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    var studyID: Int
    var onSave: (StudyRecord) -> Void
    @State private var draft = CustomQuestionDraft(language: .korean)
    @State private var ownerID: String?
    @State private var isSaving = false
    @State private var saveError: String?
    @State private var hasSaved = false

    var body: some View {
        NavigationStack {
            composerForm
                .disabled(isSaving)
                .navigationTitle(appState.strings.createCustomQuestion)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                .scrollDismissesKeyboard(.interactively)
                #endif
                .toolbar { composerToolbar }
                .interactiveDismissDisabled(isSaving)
                .onAppear(perform: loadDraft)
                .onChange(of: draft.question) { _, _ in persistChangedDraft() }
                .onChange(of: draft.answer) { _, _ in persistChangedDraft() }
                .onChange(of: appState.customQuestionDraftOwnerID) { _, _ in dismiss() }
                .onDisappear(perform: preserveDraft)
        }
    }

    private var composerForm: some View {
        Form {
            Section {
                Text(appState.strings.customQuestionHelp)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Section {
                TextEditor(text: $draft.question)
                    .frame(minHeight: 110)
                    .accessibilityLabel(appState.strings.customQuestionPrompt)
            } header: {
                Text(appState.strings.customQuestionPrompt)
            }
            Section {
                TextEditor(text: $draft.answer)
                    .frame(minHeight: 170)
                    .accessibilityLabel(appState.strings.customQuestionAnswer)
            } header: {
                Text(appState.strings.customQuestionAnswer)
            } footer: {
                Text(appState.strings.customQuestionLengthHelp)
            }
            if let saveError {
                Section {
                    Text(saveError)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    @ToolbarContentBuilder
    private var composerToolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(appState.strings.cancel) { dismiss() }
                .disabled(isSaving)
        }
        ToolbarItem(placement: .confirmationAction) {
            Button(action: save) {
                if isSaving { ProgressView() } else { Text(appState.strings.save) }
            }
            .disabled(!draft.canSave || isSaving)
        }
    }

    private func loadDraft() {
        guard ownerID == nil else { return }
        ownerID = appState.customQuestionDraftOwnerID
        draft = appState.customQuestionDraft(studyID: studyID)
    }

    private func preserveDraft() {
        guard let ownerID, !hasSaved else { return }
        appState.saveCustomQuestionDraft(draft, studyID: studyID, ownerID: ownerID)
    }

    private func persistChangedDraft() {
        guard let ownerID, ownerID == appState.customQuestionDraftOwnerID, !isSaving else { return }
        // A changed payload starts a new intention; an unchanged retry keeps its accepted identity.
        let persisted = appState.customQuestionDraft(studyID: studyID)
        if persisted.question != draft.question || persisted.answer != draft.answer {
            draft.idempotencyKey = UUID().uuidString
        }
        saveError = nil
        appState.saveCustomQuestionDraft(draft, studyID: studyID, ownerID: ownerID)
    }

    private func save() {
        guard let ownerID, ownerID == appState.customQuestionDraftOwnerID, draft.canSave, !isSaving else { return }
        isSaving = true
        saveError = nil
        Task {
            let record = await appState.createCustomQuestion(studyID: studyID, draft: draft, ownerID: ownerID)
            guard ownerID == appState.customQuestionDraftOwnerID else { return }
            if let record {
                hasSaved = true
                onSave(record)
                dismiss()
            } else {
                saveError = appState.errorMessage ?? appState.strings.communityRequestFailed
            }
            isSaving = false
        }
    }
}
