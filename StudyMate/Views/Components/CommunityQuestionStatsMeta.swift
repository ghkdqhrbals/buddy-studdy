import SwiftUI

/// Server-owned relative wording, with a date-only fallback for legacy or
/// differently localized records. No client clock computes relative labels.
struct StudyTimeDisplayText: View {
    var timeDisplay: StudyTimeDisplay?
    var timestamp: Date
    var language: AppLanguage?

    var body: some View {
        if let text = timeDisplay?.matchingText(timestamp: timestamp, language: language) {
            Text(verbatim: text)
        } else {
            Text(timestamp, format: Date.FormatStyle(date: .numeric, time: .omitted)
                .locale(language?.locale ?? .autoupdatingCurrent))
        }
    }
}

struct CommunityQuestionTopMeta: View {
    var question: CommunityQuestion
    var language: AppLanguage? = nil

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: question.recordType.symbolName)
                .accessibilityHidden(true)
            Text(question.topic.isEmpty ? "Swift" : question.topic)
                .lineLimit(1)
                .truncationMode(.tail)

            if let author = question.author, !author.displayName.isEmpty {
                HStack(spacing: 4) {
                    #if os(iOS)
                    HomeProfileAvatar(
                        symbolName: author.avatarSymbolName,
                        displayName: author.displayName,
                        colorSeed: author.avatarColorSeed,
                        size: 20
                    )
                    #else
                    Circle()
                        .fill(Color.secondary.opacity(0.35))
                        .frame(width: 20, height: 20)
                    #endif

                    Text(author.displayName)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .font(.caption.weight(.semibold))
                .accessibilityElement(children: .combine)
            }

            StudyTimeDisplayText(
                timeDisplay: question.timeDisplay,
                timestamp: question.answeredAt ?? question.createdAt,
                language: language
            )
                .fixedSize(horizontal: true, vertical: false)

            Spacer(minLength: 0)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
}

struct CommunityQuestionStatsMeta: View {
    var question: CommunityQuestion
    var strings: AppStrings

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                if let result = resultPresentation {
                    learningResult(result)
                    Spacer(minLength: 4)
                }
                engagementMetrics
            }

            VStack(alignment: .leading, spacing: 9) {
                if let result = resultPresentation {
                    learningResult(result)
                }
                engagementMetrics
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var resultPresentation: CommunityQuestionResultPresentation? {
        guard question.questionFormat != .multipleChoice || question.multipleChoice?.hasAnswered == true,
              let score = question.displayScore else {
            return nil
        }

        return CommunityQuestionResultPresentation(
            score: score,
            difficulty: question.difficultyLevel
        )
    }

    private func learningResult(_ result: CommunityQuestionResultPresentation) -> some View {
        Text(
            strings.communityQuestionResult(
                score: result.score,
                difficulty: result.difficulty
            )
        )
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .monospacedDigit()
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(Color.secondary.opacity(0.1), in: Capsule())
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            "\(strings.answerScore) \(result.score), \(strings.questionDifficulty) \(result.difficulty)"
        )
    }

    private var engagementMetrics: some View {
        HStack(spacing: 9) {
            metric(systemImage: question.isLikedByMe ? "heart.fill" : "heart", value: question.likeCount)
                .foregroundStyle(question.isLikedByMe ? .red : .secondary)
                .transaction { transaction in
                    transaction.animation = nil
                }
            metric(systemImage: "bubble.right", value: question.commentCount)
            metric(systemImage: "eye", value: question.viewCount)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: false)
    }

    private func metric(systemImage: String, value: Int) -> some View {
        HStack(spacing: 3) {
            Image(systemName: systemImage)
            Text(Self.abbreviatedCount(value))
                .monospacedDigit()
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private static func abbreviatedCount(_ value: Int) -> String {
        let absoluteValue = abs(value)

        if absoluteValue >= 999_500 {
            return compact(value, divisor: 1_000_000, suffix: "M")
        }

        if absoluteValue >= 1_000 {
            return compact(value, divisor: 1_000, suffix: "k")
        }

        return "\(value)"
    }

    private static func compact(_ value: Int, divisor: Int, suffix: String) -> String {
        let scaled = Double(value) / Double(divisor)
        let rounded = (scaled * 10).rounded() / 10

        if rounded.rounded() == rounded {
            return "\(Int(rounded))\(suffix)"
        }

        return String(format: "%.1f%@", rounded, suffix)
    }
}

struct CommunityQuestionDifficultyScale: View {
    var difficulty: Int
    var dotSize: CGFloat = 6
    var spacing: CGFloat = 4

    private var clampedDifficulty: Int {
        min(max(difficulty, 1), 10)
    }

    var body: some View {
        HStack(spacing: spacing) {
            ForEach(1...10, id: \.self) { level in
                Circle()
                    .fill(
                        level == clampedDifficulty
                            ? Color.primary
                            : Color.secondary.opacity(0.22)
                    )
                    .frame(width: dotSize, height: dotSize)
            }
        }
        .accessibilityHidden(true)
    }
}

struct CommunityQuestionResultPresentation: Equatable {
    let score: Int
    let difficulty: Int

    init(score: Int, difficulty: Int) {
        self.score = min(max(score, 0), 100)
        self.difficulty = min(max(difficulty, 1), 10)
    }
}

struct CommunityQuestionActionPolicy: Equatable {
    let canManage: Bool
    let canReport: Bool
    let canBlock: Bool

    var hasActions: Bool { canManage || canReport || canBlock }

    init(isSignedIn: Bool, isOwner: Bool?) {
        canManage = isSignedIn && isOwner == true
        canReport = isSignedIn && isOwner == false
        canBlock = isSignedIn && isOwner == false
    }

    init(isSignedIn: Bool, isOwnedByMe: Bool?, authorID: Int?, viewerIDs: Set<Int>) {
        let knownViewerIDs = viewerIDs.filter { $0 > 0 }
        let legacyOwnership: Bool?
        if knownViewerIDs.count == 1, let authorID, authorID > 0 {
            legacyOwnership = knownViewerIDs.contains(authorID)
        } else {
            legacyOwnership = nil
        }
        // Authenticated server ownership is available before /profile finishes.
        // A missing legacy identity is unknown, never evidence of another author.
        self.init(isSignedIn: isSignedIn, isOwner: isOwnedByMe ?? legacyOwnership)
    }
}

/// Shared by owned study questions and public feed cards; results appear only after an accepted answer.
struct MultipleChoiceOptionsView: View {
    var choice: MultipleChoiceQuestion
    var strings: AppStrings
    var isPending = false
    var intent: String? = nil
    var errorMessage: String? = nil
    var onSelect: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if choice.hasValidOptions {
                ForEach(choice.options) { option in
                    Group {
                        if choice.hasAnswered {
                            optionLabel(option)
                                .accessibilityElement(children: .ignore)
                        } else {
                            Button { onSelect(option.id) } label: { optionLabel(option) }
                                .buttonStyle(.plain)
                                .disabled(isPending || (intent != nil && intent != option.id))
                        }
                    }
                    .accessibilityLabel(accessibilityLabel(for: option))
                }
                if choice.hasAnswered {
                    if let total = choice.totalParticipants {
                        Text(strings.quizParticipants(total))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let correctID = choice.correctOptionId,
                       let correct = choice.options.first(where: { $0.id == correctID }) {
                        Text("\(strings.correctAnswer): \(correct.text)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.green)
                    }
                    if let explanation = choice.explanation, !explanation.isEmpty {
                        MarkdownMessageText(markdown: explanation)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(errorMessage ?? (intent != nil && !isPending ? strings.choiceSubmissionFailed : strings.chooseAnswer))
                        .font(.caption)
                        .foregroundStyle(errorMessage == nil ? Color.secondary : Color.red)
                }
            } else {
                Text(strings.choiceUnavailable).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func optionLabel(_ option: MultipleChoiceOption) -> some View {
        HStack(alignment: .center, spacing: 9) {
            Image(systemName: icon(for: option))
                .foregroundStyle(choice.hasAnswered && choice.correctOptionId == option.id ? Color.green : Color.secondary)
            Text(option.text)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            if choice.hasAnswered, let percentage = option.percentage, let count = option.count {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(Int(percentage.rounded()))%")
                        .font(.subheadline.weight(.semibold))
                    Text(strings.quizOptionCount(count))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .monospacedDigit()
            } else if isPending && intent == option.id {
                ProgressView().controlSize(.small)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 11)
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .background(Color.secondary.opacity(0.06))
        .background {
            if choice.hasAnswered, let percentage = option.percentage {
                GeometryReader { geometry in
                    Color.accentColor.opacity(0.16)
                        .frame(width: geometry.size.width * min(max(percentage, 0), 100) / 100)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(choice.selectedOptionId == option.id ? Color.accentColor : Color.secondary.opacity(0.25), lineWidth: choice.selectedOptionId == option.id ? 1.5 : 1))
        .contentShape(Rectangle())
    }

    private func icon(for option: MultipleChoiceOption) -> String {
        if choice.hasAnswered && choice.correctOptionId == option.id { return "checkmark.circle.fill" }
        if (choice.selectedOptionId ?? intent) == option.id { return "largecircle.fill.circle" }
        return "circle"
    }

    private func accessibilityLabel(for option: MultipleChoiceOption) -> String {
        var label = option.text
        if choice.selectedOptionId == option.id { label += ", " + strings.selectedAnswer }
        if choice.hasAnswered && choice.correctOptionId == option.id { label += ", " + strings.correctAnswer }
        if choice.hasAnswered, let count = option.count, let percentage = option.percentage {
            label += ", \(Int(percentage.rounded()))%, " + strings.quizOptionCount(count)
        }
        return label
    }
}
