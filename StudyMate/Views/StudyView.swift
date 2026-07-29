import SwiftUI
#if os(iOS)
import MarkdownUI
#endif

struct StudyView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    var preferredCategoryID: String? = nil
    @State private var showsHint = false
    @State private var draftAnswer = ""
    @State private var showsPendingLimitHelp = false
    @State private var editingStudyRoom: BackendStudyRoom?
    @State private var selectedTreeRootID: Int?
    @State private var answerSubmissionTask: Task<Void, Never>?
    @State private var answerGradingOwnerID: String?
    #if os(iOS)
    @FocusState private var isAnswerEditorFocused: Bool
    #endif

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
                        StudyConversationSection(
                            question: record.question,
                            draftAnswer: $draftAnswer,
                            showsHint: $showsHint,
                            gradingResult: record.gradingResult,
                            isGradingAnswer: appState.isGradingAnswer,
                            gradingStatusMessage: appState.answerGradingStatusMessage,
                            canSubmitAnswer: canSubmitAnswer,
                            strings: strings,
                            answerEditor: {
                                answerEditor()
                            },
                            onSubmit: submitCurrentAnswer,
                            onSkip: {
                                appState.skipStudyRoomRecord(record)
                            }
                        )
                    } else if appState.isGeneratingQuestion(categoryID: targetCategoryID) {
                        questionLoadingMessage(strings: strings)
                            .padding(.top, 4)
                    } else {
                        noQuestionView(strings: strings)
                            .frame(maxWidth: .infinity, minHeight: 140)
                    }
                }
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
            StudyTopicLevelSheet(
                room: room,
                strings: strings,
                onDelete: {
                    deleteStudyRoom(room)
                }
            ) { title, difficulty, isActive in
                appState.updateStudyTreeCategory(
                    roomID: room.id,
                    title: title,
                    difficulty: difficulty
                )
                if isActive != room.activeForQuestions {
                    appState.setStudyTopicActive(studyID: room.id, active: isActive)
                }
            }
        }
        .alert(strings.pendingQuestionLimitTitle, isPresented: $showsPendingLimitHelp) {
            Button(strings.done, role: .cancel) {}
        } message: {
            Text(strings.pendingQuestionLimitMessage)
        }
        .onAppear {
            draftAnswer = appState.answerDraft(for: selectedStudyRecord)
            presentPendingLimitNoticeIfNeeded()
        }
        .task(id: preferredCategoryID) {
            async let roomPreparation: Void = appState.prepareStudyRoom(categoryID: preferredCategoryID)
            async let quotaRefresh: Void = appState.refreshQuestionQuota()
            _ = await (roomPreparation, quotaRefresh)
        }
        .onDisappear {
            answerSubmissionTask?.cancel()
            answerSubmissionTask = nil
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
               draftAnswer != appState.answerDraft(for: selectedStudyRecord) {
                appState.updateAnswer(draftAnswer, for: selectedStudyRecord)
            }
        }
        .onChange(of: selectedStudyRecord?.id) {
            showsHint = false
            draftAnswer = appState.answerDraft(for: selectedStudyRecord)
        }
        .onChange(of: selectedStudyRecord?.answer) {
            if draftAnswer != appState.answerDraft(for: selectedStudyRecord) {
                draftAnswer = appState.answerDraft(for: selectedStudyRecord)
            }
        }
        .onChange(of: appState.pendingQuestionLimitCategoryID) {
            presentPendingLimitNoticeIfNeeded()
        }
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
        selectedStudyRecord?.gradingResult == nil &&
            !draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !appState.isGradingAnswer
    }

    private var selectedStudyRecord: StudyRecord? {
        appState.studyRoomRecordForDisplay(categoryID: preferredCategoryID)
    }

    private var selectedDifficulty: Difficulty {
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
        guard let selectedStudyRecord else {
            return
        }

        #if os(iOS)
        isAnswerEditorFocused = false
        #endif

        answerSubmissionTask?.cancel()
        let ownerID = UUID().uuidString
        answerGradingOwnerID = ownerID
        answerSubmissionTask = Task {
            await appState.gradeStudyRoomRecord(
                selectedStudyRecord,
                answer: draftAnswer,
                pollingOwnerID: ownerID
            )
            guard answerGradingOwnerID == ownerID else {
                return
            }
            answerSubmissionTask = nil
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
    var gradingResult: GradingResult?
    var isGradingAnswer: Bool
    var gradingStatusMessage: String?
    var canSubmitAnswer: Bool
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

                        if gradingResult == nil {
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

                    hintView
                }
            }

            if gradingResult == nil && !isGradingAnswer {
                StudyChatBubble(role: .learnerInput) {
                    MessageAnswerInput(
                        strings: strings,
                        isGradingAnswer: isGradingAnswer,
                        canSubmitAnswer: canSubmitAnswer,
                        answerEditor: answerEditor,
                        onSubmit: onSubmit
                    )
                }
            } else if !draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                StudyChatBubble(role: .learnerAnswer) {
                    MarkdownMessageText(markdown: draftAnswer, fillsWidth: false)
                        .font(.body)
                        .foregroundStyle(.white)
                        .tint(.white)
                        .textSelection(.enabled)
                        .multilineTextAlignment(.leading)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 13)
                        .background(Color.green.opacity(0.92), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            }

            if isGradingAnswer, let gradingStatusMessage {
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
