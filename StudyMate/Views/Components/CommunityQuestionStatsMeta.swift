import SwiftUI

struct CommunityQuestionTopMeta: View {
    var question: CommunityQuestion

    var body: some View {
        HStack(spacing: 7) {
            HStack(spacing: 5) {
                Text(question.topic.isEmpty ? "Swift" : question.topic)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Text("Lv.\(question.difficultyLevel)")
                    .fixedSize(horizontal: true, vertical: false)
            }

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

    var body: some View {
        HStack(spacing: 8) {
            metric(systemImage: question.isLikedByMe ? "heart.fill" : "heart", value: question.likeCount)
                .foregroundStyle(question.isLikedByMe ? .red : .secondary)
                .transaction { transaction in
                    transaction.animation = nil
                }
            metric(systemImage: "bubble.right", value: question.commentCount)
            metric(systemImage: "eye", value: question.viewCount)

            Spacer(minLength: 0)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.secondary)
        .lineLimit(1)
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
