import SwiftUI

struct StudyView: View {
    @EnvironmentObject private var appState: AppState
    var preferredCategoryID: String? = nil
    @State private var showsHint = false
    @State private var draftAnswer = ""
    @State private var showsPendingLimitHelp = false
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
            ToolbarItem(placement: .topBarTrailing) {
                toolbarNewQuestionButton(strings: strings)
            }
            #endif
        }
        .alert(strings.pendingQuestionLimitTitle, isPresented: $showsPendingLimitHelp) {
            Button(strings.done, role: .cancel) {}
        } message: {
            Text(strings.pendingQuestionLimitMessage)
        }
        .onAppear {
            draftAnswer = appState.answerDraft(for: selectedStudyRecord)
        }
        .task(id: preferredCategoryID) {
            async let roomPreparation: Void = appState.prepareStudyRoom(categoryID: preferredCategoryID)
            async let quotaRefresh: Void = appState.refreshQuestionQuota()
            _ = await (roomPreparation, quotaRefresh)
        }
        .onDisappear {
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
    }

    private var canSubmitAnswer: Bool {
        selectedStudyRecord?.gradingResult == nil &&
            !draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !appState.isGradingAnswer
    }

    private var selectedStudyRecord: StudyRecord? {
        appState.pendingStudyRecord(categoryID: preferredCategoryID)
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

    @ViewBuilder
    private func newQuestionButton(strings: AppStrings, prominent: Bool = false) -> some View {
        if prominent {
            Button {
                requestNewQuestion()
            } label: {
                newQuestionButtonLabel(strings: strings)
            }
            .buttonStyle(.borderedProminent)
            .disabled(appState.isGeneratingQuestion)
            .opacity(hasReachedPendingQuestionLimit ? 0.55 : 1)
            .accessibilityHint(hasReachedPendingQuestionLimit ? strings.pendingQuestionLimitMessage : "")
        } else {
            Button {
                requestNewQuestion()
            } label: {
                newQuestionButtonLabel(strings: strings)
            }
            .buttonStyle(.bordered)
            .disabled(appState.isGeneratingQuestion)
            .opacity(hasReachedPendingQuestionLimit ? 0.55 : 1)
            .accessibilityHint(hasReachedPendingQuestionLimit ? strings.pendingQuestionLimitMessage : "")
        }
    }

    private func toolbarNewQuestionButton(strings: AppStrings) -> some View {
        Button {
            requestNewQuestion()
        } label: {
            if appState.isGeneratingQuestion {
                ProgressView()
                    .controlSize(.small)
            } else {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .semibold))
            }
        }
        .disabled(appState.isGeneratingQuestion)
        .opacity(hasReachedPendingQuestionLimit ? 0.55 : 1)
        .accessibilityLabel(strings.newQuestion)
        .accessibilityHint(hasReachedPendingQuestionLimit ? strings.pendingQuestionLimitMessage : "")
    }

    @ViewBuilder
    private func newQuestionButtonLabel(strings: AppStrings) -> some View {
        if appState.isGeneratingQuestion {
            ProgressView()
                .controlSize(.small)
        } else {
            Label(strings.newQuestion, systemImage: "plus.circle")
        }
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

        Task {
            await appState.gradeStudyRoomRecord(selectedStudyRecord, answer: draftAnswer)
        }
    }
}

private struct StudyConversationSection<AnswerEditorContent: View>: View {
    var question: QuestionItem
    @Binding var draftAnswer: String
    @Binding var showsHint: Bool
    var gradingResult: GradingResult?
    var isGradingAnswer: Bool
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
                        Text(question.question)
                            .font(.body)
                            .foregroundStyle(.white)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        if gradingResult == nil {
                            Button {
                                onSkip()
                            } label: {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.9))
                                    .frame(width: 30, height: 30)
                                    .background(.white.opacity(0.16), in: Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(strings.skipQuestion)
                        }
                    }

                    hintView
                }
            }

            if gradingResult == nil {
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
                    Text(draftAnswer)
                        .font(.body)
                        .foregroundStyle(.white)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 13)
                        .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
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

                        Text(gradingResult.feedback)
                            .font(.body)
                        Text(gradingResult.explanation)
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
                .foregroundStyle(.white)
                .tint(.white)

                if showsHint {
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                        .textSelection(.enabled)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
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
            Color.green.opacity(0.92)
        case .learnerInput, .learnerAnswer:
            Color.clear
        case .feedback:
            Color.secondary.opacity(0.06)
        }
    }

    var borderColor: Color {
        switch self {
        case .tutor:
            Color.green.opacity(0.0)
        case .learnerInput, .learnerAnswer:
            Color.clear
        case .feedback:
            Color.secondary.opacity(0.12)
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
                content()
                    .frame(minWidth: 44, maxWidth: 280, alignment: .trailing)
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
