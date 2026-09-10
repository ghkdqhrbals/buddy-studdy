#if os(iOS)
import SwiftUI

struct VoiceTutorUserInputCard: View {
    let entry: VoiceTutorUserInputState.Entry
    let strings: AppStrings
    var onChange: (VoiceTutorUserInputAnswer) -> Void
    var onSubmit: () -> Void
    var onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(entry.request.title).font(.headline)
            if entry.isWaiting {
                Text(strings.voiceTutorInputWaiting).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(entry.request.questions) { question in
                questionView(question)
            }
            if entry.hasError {
                Text(entry.actionFailed ? strings.voiceTutorInputActionFailed : strings.voiceTutorInputInvalid).font(.caption).foregroundStyle(.red)
            }
            if entry.isWaiting {
                HStack {
                    Button(strings.cancel, action: onCancel)
                        .disabled(entry.status != .pending)
                    Spacer()
                    if entry.status == .submitting { ProgressView() }
                    Button(strings.voiceTutorInputContinue, action: onSubmit)
                        .buttonStyle(.borderedProminent)
                        .disabled(!entry.canSubmit)
                        .accessibilityIdentifier("voiceConversation.inputSubmit")
                }
            } else {
                Label(entry.status == .submitted ? strings.voiceTutorInputSubmitted : strings.voiceTutorInputCancelled,
                      systemImage: entry.status == .submitted ? "checkmark.circle" : "minus.circle")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityIdentifier("voiceConversation.userInput")
    }

    private func questionView(_ question: VoiceTutorUserInputQuestion) -> some View {
        let answer = entry.answers.first(where: { $0.questionId == question.id }) ?? .init(questionId: question.id)
        return VStack(alignment: .leading, spacing: 10) {
            Text(question.prompt).font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            if question.selectionMode != .text, entry.isWaiting {
                Text(strings.voiceTutorInputSelectionHelp(multiple: question.selectionMode == .multiple))
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(question.options) { option in
                let selected = answer.selectedOptionIds.contains(option.id)
                Button {
                    var updated = answer
                    if selected { updated.selectedOptionIds.removeAll { $0 == option.id } }
                    else if question.selectionMode == .single { updated.selectedOptionIds = [option.id] }
                    else { updated.selectedOptionIds.append(option.id) }
                    onChange(updated)
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                        Text(option.label).foregroundStyle(.primary).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .font(.subheadline)
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(selected ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.04),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(entry.status != .pending)
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("voiceConversation.option.\(question.id).\(option.id)")
            }
            if question.allowFreeText {
                if entry.isWaiting {
                    Text(strings.voiceTutorInputCustom).font(.caption).foregroundStyle(.secondary)
                    TextEditor(text: Binding(get: { answer.text }, set: { text in
                        var updated = answer
                        updated.text = text
                        onChange(updated)
                    }))
                    .font(.body)
                    .frame(minHeight: 100, maxHeight: 160)
                    .padding(8)
                    .scrollContentBackground(.hidden)
                    .background(Color(uiColor: .systemBackground), in: RoundedRectangle(cornerRadius: 12))
                    .disabled(entry.status != .pending)
                    .accessibilityLabel(question.prompt + ". " + strings.voiceTutorInputCustom)
                    .accessibilityIdentifier("voiceConversation.inputText.\(question.id)")
                } else if !answer.text.isEmpty {
                    Text(answer.text).font(.body).textSelection(.enabled)
                }
            }
        }
    }
}

extension AppStrings {
    var voiceTutorInputWaiting: String {
        inputText("선택을 기다리고 있어요. 제출하면 대화를 이어갑니다.", "Conversation paused for your input. Submit to continue.", "選択を待っています。送信すると対話を続けます。")
    }
    var voiceTutorInputStatus: String { inputText("선택 기다리는 중", "Waiting for your input", "選択を待機中") }
    var voiceTutorInputInvalid: String { inputText("각 질문의 선택이나 입력을 확인해 주세요.", "Check your selection or text for each question.", "各質問の選択や入力を確認してください。") }
    var voiceTutorInputActionFailed: String { inputText("선택을 적용하지 못했어요. 입력은 남아 있으니 다시 제출해 주세요.", "Couldn't apply your selection. Your input is saved here; submit again to retry.", "選択を適用できませんでした。入力は残っています。もう一度送信してください。") }
    var voiceTutorInputContinue: String { inputText("제출하고 계속", "Submit and continue", "送信して続ける") }
    var voiceTutorInputSubmitted: String { inputText("제출 완료", "Submitted", "送信済み") }
    var voiceTutorInputCancelled: String { inputText("취소됨", "Cancelled", "キャンセル済み") }
    var voiceTutorInputCustom: String { inputText("직접 입력", "Write your own answer", "自分で入力") }
    func voiceTutorInputSelectionHelp(multiple: Bool) -> String {
        multiple ? inputText("여러 개 선택 가능", "Choose any that apply", "複数選択可") : inputText("하나 선택", "Choose one", "一つ選択")
    }
    private func inputText(_ ko: String, _ en: String, _ ja: String) -> String {
        switch language { case .korean: ko; case .english: en; case .japanese: ja }
    }
}
#endif
