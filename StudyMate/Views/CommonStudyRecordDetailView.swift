#if os(iOS)
import SwiftUI

/// Records-tab and study-tree entries share the same canonical record detail,
/// ownership actions, and (when public) comments/likes. No answer page is opened
/// merely because a voice exchange has no score.
struct CommonStudyRecordDetailView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    let record: StudyRecord
    @State private var confirmsDeletion = false
    @State private var presentedIdentity: CommonRecordsIdentity?

    private var currentRecord: StudyRecord {
        StudyRecordIdentityPolicy.cachedRecord(matching: record, in: appState.studyRecords) ?? record
    }

    var body: some View {
        Group {
            if currentRecord.isCompletedRecord {
                CommunityQuestionDetailView(
                    question: currentRecord.asQuestionBrowseQuestion(author: appState.communityProfile),
                    contentSource: .record(isPublic: currentRecord.isPublic && currentRecord.canPublish)
                )
            } else {
                StudyRecordDetailView(record: currentRecord).padding(.horizontal, 16)
            }
        }
        .navigationTitle(appState.strings.commonRecordTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if #available(iOS 26.0, *) {
                ToolbarItem(placement: .topBarTrailing) { actions }
                    .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .topBarTrailing) { actions }
            }
        }
        .confirmationDialog(appState.strings.deleteQuestionConfirmation, isPresented: $confirmsDeletion) {
            Button(appState.strings.clear, role: .destructive) {
                guard presentedIdentity == appState.commonRecordsIdentity else { dismiss(); return }
                appState.deleteStudyRecord(currentRecord)
                dismiss()
            }
            Button(appState.strings.cancel, role: .cancel) {}
        }
        .onAppear { if presentedIdentity == nil { presentedIdentity = appState.commonRecordsIdentity } }
        .onChange(of: appState.commonRecordsIdentity) { _, _ in dismiss() }
    }

    private var actions: some View {
        Menu {
            if appState.isCommunitySessionActive, currentRecord.isPublic || currentRecord.canPublish {
                Button {
                    guard presentedIdentity == appState.commonRecordsIdentity else { dismiss(); return }
                    appState.updateStudyRecordPublicity(currentRecord, isPublic: !currentRecord.isPublic)
                } label: {
                    Label(
                        currentRecord.isPublic ? appState.strings.makeQuestionPrivate : appState.strings.makeQuestionPublic,
                        systemImage: currentRecord.isPublic ? "lock.fill" : "globe"
                    )
                }
            }
            Button(role: .destructive) { confirmsDeletion = true } label: {
                Label(appState.strings.clear, systemImage: "trash")
            }
        } label: {
            MobileToolbarIconButtonLabel(systemName: "ellipsis")
        }
        .accessibilityLabel(appState.strings.more)
        .disabled(presentedIdentity != appState.commonRecordsIdentity)
    }
}

/// Only real exchange feedback is shown. This never fabricates a GradingResult
/// or treats an absent assessment/learner question as a zero score.
struct VoiceRecordFeedbackContent: View {
    let content: VoiceRecordContent
    let answer: String?
    let strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let feedback = nonempty(content.feedback) {
                section(strings.voiceRecordFeedback, body: feedback)
            }
            list(strings.voiceRecordStrengths, values: content.strengths)
            list(strings.voiceRecordImprovements, values: content.improvements)
            if let depth = nonempty(content.depthSummary) {
                DisclosureGroup(strings.voiceRecordDepth) {
                    MarkdownMessageText(markdown: depth)
                        .font(.subheadline).textSelection(.enabled).padding(.top, 6)
                }
                .font(.subheadline)
            }
            if let score = content.displayScore(answer: answer) {
                Text("\(score)/100").font(.caption.weight(.semibold)).monospacedDigit()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func section(_ title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            MarkdownMessageText(markdown: body).font(.subheadline).textSelection(.enabled)
        }
    }

    @ViewBuilder
    private func list(_ title: String, values: [String]) -> some View {
        let visible = values.compactMap(nonempty)
        if !visible.isEmpty {
            section(title, body: visible.map { "• \($0)" }.joined(separator: "\n"))
        }
    }

    private func nonempty(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return text
    }
}
#endif
