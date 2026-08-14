import SwiftUI

struct CommunityQuestionTopMeta: View {
    var question: CommunityQuestion

    var body: some View {
        HStack(spacing: 7) {
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

            Text(StudyDateDisplayFormatter.relativeOrShortDateString(for: question.createdAt))
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
        guard let score = question.gradingResult?.score else {
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

    init(isSignedIn: Bool, isOwner: Bool) {
        canManage = isSignedIn && isOwner
        canReport = isSignedIn && !isOwner
        canBlock = isSignedIn && !isOwner
    }
}
