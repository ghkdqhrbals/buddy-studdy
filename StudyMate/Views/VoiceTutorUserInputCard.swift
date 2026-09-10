#if os(iOS)
import SwiftUI

struct VoiceTutorUserInputCard: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var editingQuestion: VoiceTutorUserInputQuestion?
    let entry: VoiceTutorUserInputState.Entry
    let strings: AppStrings
    var onChange: (VoiceTutorUserInputAnswer) -> Void
    var onSubmit: () -> Void
    var onCancel: () -> Void

    private var accent: Color {
        colorScheme == .dark
            ? Color(red: 0.42, green: 0.82, blue: 0.77)
            : Color(red: 0.04, green: 0.43, blue: 0.40)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            if entry.isWaiting {
                ForEach(Array(entry.request.questions.enumerated()), id: \.element.id) { index, question in
                    if index > 0 { Divider().overlay(Color.secondary.opacity(0.08)) }
                    questionView(question)
                }
                if entry.hasError {
                    Label(entry.actionFailed ? strings.voiceTutorInputActionFailed : strings.voiceTutorInputInvalid,
                          systemImage: "exclamationmark.circle")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
                footer
            } else {
                completedAnswers
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.045), in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .strokeBorder(Color.secondary.opacity(0.12), lineWidth: 0.75)
                .allowsHitTesting(false)
        }
        .tint(accent)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: entry.isWaiting)
        .accessibilityIdentifier("voiceConversation.userInput")
        .sheet(item: $editingQuestion) { question in
            VoiceTutorUserInputEditor(strings: strings, question: question,
                text: textBinding(for: question), isEditable: entry.status == .pending)
                .tint(accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .onChange(of: entry.status) { _, status in
            if status != .pending { editingQuestion = nil }
        }
        .onChange(of: entry.id) { _, _ in editingQuestion = nil }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(entry.request.title)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Label(statusLabel, systemImage: entry.isWaiting ? "pause.circle" :
                    (entry.status == .submitted ? "checkmark.circle" : "minus.circle"))
                .font(.caption2.weight(.medium))
                .foregroundStyle(entry.isWaiting ? accent : .secondary)
                .fixedSize()
        }
    }

    private var statusLabel: String {
        if entry.isWaiting { return strings.voiceTutorInputWaitingShort }
        return entry.status == .submitted ? strings.voiceTutorInputSubmitted : strings.voiceTutorInputCancelled
    }

    private func answer(for question: VoiceTutorUserInputQuestion) -> VoiceTutorUserInputAnswer {
        entry.answers.first(where: { $0.questionId == question.id }) ?? .init(questionId: question.id)
    }

    private func textBinding(for question: VoiceTutorUserInputQuestion) -> Binding<String> {
        Binding(get: { answer(for: question).text }, set: { text in
            guard entry.status == .pending,
                  entry.request.questions.contains(where: { $0.id == question.id }) else { return }
            var updated = answer(for: question)
            updated.text = text
            onChange(updated)
        })
    }

    private func questionView(_ question: VoiceTutorUserInputQuestion) -> some View {
        let answer = answer(for: question)
        return VStack(alignment: .leading, spacing: 12) {
            Text(question.prompt)
                .font(.body.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            if question.selectionMode != .text {
                HStack {
                    Text(strings.voiceTutorInputSelectionHelp(multiple: question.selectionMode == .multiple))
                    Spacer(minLength: 8)
                    if !answer.selectedOptionIds.isEmpty {
                        Text(strings.voiceTutorInputSelectedCount(answer.selectedOptionIds.count))
                            .foregroundStyle(accent)
                            .contentTransition(.numericText())
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
                .accessibilityElement(children: .combine)
                options(question, answer: answer)
            }
            if question.allowFreeText { customInput(question, answer: answer) }
        }
    }

    private func options(_ question: VoiceTutorUserInputQuestion, answer: VoiceTutorUserInputAnswer) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(question.options.enumerated()), id: \.element.id) { index, option in
                let selected = answer.selectedOptionIds.contains(option.id)
                if index > 0 {
                    Rectangle().fill(Color.secondary.opacity(0.12)).frame(height: 0.5)
                        .padding(.horizontal, 14)
                }
                Button {
                    guard entry.status == .pending else { return }
                    var updated = answer
                    if selected { updated.selectedOptionIds.removeAll { $0 == option.id } }
                    else if question.selectionMode == .single { updated.selectedOptionIds = [option.id] }
                    else { updated.selectedOptionIds.append(option.id) }
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.16)) { onChange(updated) }
                } label: {
                    HStack(spacing: 14) {
                        Text(option.label)
                            .font(.subheadline.weight(selected ? .medium : .regular))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                        Image(systemName: selected
                              ? (question.selectionMode == .multiple ? "checkmark.square.fill" : "checkmark.circle.fill")
                              : (question.selectionMode == .multiple ? "square" : "circle"))
                            .font(.body)
                            .foregroundStyle(selected ? accent : Color.secondary.opacity(0.55))
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .background(selected ? accent.opacity(0.09) : Color.clear)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(entry.status != .pending)
                .accessibilityLabel(option.label)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("voiceConversation.option.\(question.id).\(option.id)")
            }
        }
        .background(Color.secondary.opacity(0.025))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay {
            RoundedRectangle(cornerRadius: 14).strokeBorder(Color.secondary.opacity(0.15), lineWidth: 0.75)
                .allowsHitTesting(false)
        }
    }

    private func customInput(_ question: VoiceTutorUserInputQuestion, answer: VoiceTutorUserInputAnswer) -> some View {
        Button {
            guard entry.status == .pending else { return }
            editingQuestion = question
        } label: {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "square.and.pencil")
                    .font(.body)
                    .foregroundStyle(accent)
                VStack(alignment: .leading, spacing: 5) {
                    Text(strings.voiceTutorInputCustom)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(verbatim: answer.text.isEmpty ? strings.voiceTutorInputCustomHint : answer.text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(answer.text.isEmpty ? 1 : 2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .background(Color.secondary.opacity(0.04), in: RoundedRectangle(cornerRadius: 14))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(entry.status != .pending)
        .accessibilityLabel(strings.voiceTutorInputCustom)
        .accessibilityValue(answer.text)
        .accessibilityHint(question.prompt)
        .accessibilityIdentifier("voiceConversation.inputText.\(question.id)")
    }

    private var footer: some View {
        VStack(spacing: 14) {
            Rectangle().fill(Color.secondary.opacity(0.12)).frame(height: 0.5)
            HStack(spacing: 12) {
                Button(strings.cancel, action: onCancel)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 56, minHeight: 46)
                    .disabled(entry.status != .pending)
                Button(action: onSubmit) {
                    HStack(spacing: 8) {
                        if entry.status == .submitting { ProgressView().tint(.primary) }
                        Text(entry.status == .submitting ? strings.voiceTutorInputSending : strings.voiceTutorInputSubmit)
                            .font(.subheadline.weight(.semibold))
                        if entry.status != .submitting { Image(systemName: "arrow.right").font(.caption.weight(.semibold)) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .foregroundStyle(entry.canSubmit ? Color(uiColor: .systemBackground) : Color.secondary)
                    .background(entry.canSubmit ? accent : Color.secondary.opacity(0.1),
                                in: RoundedRectangle(cornerRadius: 13))
                }
                .buttonStyle(.plain)
                .disabled(!entry.canSubmit)
                .accessibilityLabel(strings.voiceTutorInputContinue)
                .accessibilityIdentifier("voiceConversation.inputSubmit")
            }
        }
    }

    private var completedAnswers: some View {
        let displayedAnswers = entry.status == .submitted ? entry.submittedAnswers ?? [] : entry.answers
        return VStack(alignment: .leading, spacing: 14) {
            if displayedAnswers.contains(where: { !$0.selectedOptionIds.isEmpty || !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
                Text(entry.status == .submitted ? strings.voiceTutorInputSubmittedAnswers : strings.voiceTutorInputUnsubmittedDraft)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier(entry.status == .submitted
                        ? "voiceConversation.submittedAnswers" : "voiceConversation.unsubmittedDraft")
            }
            ForEach(entry.request.questions) { question in
                let answer = displayedAnswers.first { $0.questionId == question.id } ?? .init(questionId: question.id)
                let labels = question.options.filter { answer.selectedOptionIds.contains($0.id) }.map(\.label)
                if !labels.isEmpty || !answer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(question.prompt).font(.caption).foregroundStyle(.secondary)
                        if !labels.isEmpty {
                            Text(labels.joined(separator: " · "))
                                .font(.subheadline.weight(.medium))
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("voiceConversation.inputResultOptions.\(question.id)")
                        }
                        if !answer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(strings.voiceTutorInputCustom).font(.caption).foregroundStyle(.secondary)
                            Text(verbatim: answer.text).font(.subheadline)
                                .fixedSize(horizontal: false, vertical: true)
                                .textSelection(.enabled)
                                .accessibilityIdentifier("voiceConversation.inputResultText.\(question.id)")
                        }
                    }
                }
            }
        }
        .foregroundStyle(entry.status == .submitted ? Color.primary : Color.secondary)
    }
}

/// A dedicated native editor separates caret/selection gestures from the live
/// transcript. Changes stay in the request draft; Done never submits a form.
struct VoiceTutorUserInputEditor: View {
    @Environment(\.dismiss) private var dismiss
    @FocusState private var isFocused: Bool
    let strings: AppStrings
    let question: VoiceTutorUserInputQuestion
    @Binding var text: String
    var isEditable = true

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(question.prompt)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
                    .accessibilityLabel(question.prompt)
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text(strings.voiceTutorInputPlaceholder)
                            .font(.body)
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                    TextEditor(text: $text)
                        .font(.body)
                        .scrollContentBackground(.hidden)
                        .scrollDismissesKeyboard(.never)
                        .focused($isFocused)
                        .disabled(!isEditable)
                        .accessibilityLabel(question.prompt + ". " + strings.voiceTutorInputCustom)
                        .accessibilityIdentifier("voiceConversation.inputEditor")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)
            .background(Color(uiColor: .systemBackground))
            .navigationTitle(strings.voiceTutorInputCustom)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.done) {
                        isFocused = false
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("voiceConversation.inputEditorDone")
                }
            }
            .task { if isEditable { isFocused = true } }
            .onChange(of: isEditable) { _, editable in
                if !editable { isFocused = false }
            }
        }
    }
}

extension AppStrings {
    var voiceTutorInputWaiting: String {
        inputText("선택을 기다리고 있어요. 제출하면 대화를 이어갑니다.", "Conversation paused for your input. Submit to continue.", "選択を待っています。送信すると対話を続けます。")
    }
    var voiceTutorInputWaitingShort: String { inputText("응답 대기", "Awaiting input", "回答待ち") }
    var voiceTutorInputStatus: String { inputText("선택 기다리는 중", "Waiting for your input", "選択を待機中") }
    var voiceTutorInputInvalid: String { inputText("각 질문의 선택이나 입력을 확인해 주세요.", "Check your selection or text for each question.", "各質問の選択や入力を確認してください。") }
    var voiceTutorInputActionFailed: String { inputText("선택을 적용하지 못했어요. 입력은 남아 있으니 다시 제출해 주세요.", "Couldn't apply your selection. Your input is saved here; submit again to retry.", "選択を適用できませんでした。入力は残っています。もう一度送信してください。") }
    var voiceTutorInputContinue: String { inputText("제출하고 계속", "Submit and continue", "送信して続ける") }
    var voiceTutorInputSubmit: String { inputText("제출", "Submit", "送信") }
    var voiceTutorInputSending: String { inputText("보내는 중", "Sending", "送信中") }
    var voiceTutorInputSubmitted: String { inputText("제출 완료", "Submitted", "送信済み") }
    var voiceTutorInputCancelled: String { inputText("취소됨", "Cancelled", "キャンセル済み") }
    var voiceTutorInputSubmittedAnswers: String { inputText("제출한 답변", "Submitted answers", "送信した回答") }
    var voiceTutorInputUnsubmittedDraft: String { inputText("제출 미확인 초안", "Draft · submission unconfirmed", "送信未確認の下書き") }
    var voiceTutorInputCustom: String { inputText("직접 입력", "Write your own answer", "自分で入力") }
    var voiceTutorInputCustomHint: String { inputText("다른 생각이나 원하는 방향을 적어주세요", "Add your thoughts or another direction", "考えや希望の方向を入力") }
    var voiceTutorInputPlaceholder: String { inputText("여기에 자유롭게 적어주세요.", "Write your thoughts here.", "ここに自由に入力してください。") }
    func voiceTutorInputSelectedCount(_ count: Int) -> String {
        inputText("\(count)개 선택", "\(count) selected", "\(count)件選択")
    }
    func voiceTutorInputSelectionHelp(multiple: Bool) -> String {
        multiple ? inputText("여러 개 선택 가능", "Choose any that apply", "複数選択可") : inputText("하나 선택", "Choose one", "一つ選択")
    }
    private func inputText(_ ko: String, _ en: String, _ ja: String) -> String {
        switch language { case .korean: ko; case .english: en; case .japanese: ja }
    }
}
#endif
